// PairDesk screen capture for Windows: DXGI Desktop Duplication.
//
// Desktop Duplication gives the picture of a screen *without* the mouse
// cursor (it reports the cursor separately; PairDesk ignores it, the viewer
// draws its own), and only when something changed. Loaded with koffi by the
// capture helper process (src/main/capture/capture-win32.js).
//
//   void *pdcOpen(int left, int top, int width, int height, int halve, char *err, int errLen)
//       Opens the display whose desktop rectangle starts at (left, top) and
//       has this size (physical pixels). halve = 1 halves both dimensions.
//   int pdcWidth(void *h), pdcHeight(void *h)   size of the pictures produced
//   int pdcNext(void *h, int timeoutMs, uint8_t *dst, int dstSize, char *err, int errLen)
//       Waits up to timeoutMs for a change. 1: new picture copied into dst
//       (BGRA, rows of width * 4 bytes); 0: nothing new (also while the
//       desktop is not reachable, e.g. a UAC prompt); -1: error (message in
//       err), the handle must be closed.
//   void pdcClose(void *h)

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <d3d11.h>
#include <dxgi1_2.h>

#include <cstdint>
#include <cstdio>
#include <cstring>

namespace {

template <typename T>
void release(T *&p) {
  if (p) {
    p->Release();
    p = nullptr;
  }
}

void setError(char *err, int errLen, const char *what, HRESULT hr) {
  if (err && errLen > 0) snprintf(err, errLen, "%s (0x%08lx)", what, static_cast<unsigned long>(hr));
}

struct Capture {
  int left = 0, top = 0, width = 0, height = 0;
  bool halve = false;
  IDXGIAdapter1 *adapter = nullptr;
  IDXGIOutput1 *output = nullptr;
  ID3D11Device *device = nullptr;
  ID3D11DeviceContext *context = nullptr;
  IDXGIOutputDuplication *dup = nullptr;
  ID3D11Texture2D *staging = nullptr;
  bool frameHeld = false;
  DWORD retryAt = 0; // after a lost duplication: when to try again

  int outWidth() const { return halve ? width / 2 : width; }
  int outHeight() const { return halve ? height / 2 : height; }

  void releaseDuplication() {
    if (frameHeld && dup) dup->ReleaseFrame();
    frameHeld = false;
    release(dup);
  }

  ~Capture() {
    releaseDuplication();
    release(staging);
    release(context);
    release(device);
    release(output);
    release(adapter);
  }

  // Finds the output (and its adapter) showing the desktop at (left, top).
  bool findOutput(char *err, int errLen) {
    IDXGIFactory1 *factory = nullptr;
    HRESULT hr = CreateDXGIFactory1(__uuidof(IDXGIFactory1), reinterpret_cast<void **>(&factory));
    if (FAILED(hr)) {
      setError(err, errLen, "CreateDXGIFactory1 failed", hr);
      return false;
    }
    for (UINT a = 0; !output; a++) {
      IDXGIAdapter1 *ad = nullptr;
      if (factory->EnumAdapters1(a, &ad) == DXGI_ERROR_NOT_FOUND) break;
      for (UINT o = 0;; o++) {
        IDXGIOutput *out = nullptr;
        if (ad->EnumOutputs(o, &out) == DXGI_ERROR_NOT_FOUND) break;
        DXGI_OUTPUT_DESC desc;
        if (SUCCEEDED(out->GetDesc(&desc)) && desc.AttachedToDesktop && desc.DesktopCoordinates.left == left &&
            desc.DesktopCoordinates.top == top) {
          if (desc.Rotation != DXGI_MODE_ROTATION_IDENTITY && desc.Rotation != DXGI_MODE_ROTATION_UNSPECIFIED) {
            setError(err, errLen, "rotated display", E_NOTIMPL);
            out->Release();
            ad->Release();
            factory->Release();
            return false;
          }
          if (SUCCEEDED(out->QueryInterface(__uuidof(IDXGIOutput1), reinterpret_cast<void **>(&output)))) {
            adapter = ad;
            ad = nullptr;
          }
        }
        out->Release();
        if (output) break;
      }
      release(ad);
    }
    factory->Release();
    if (!output) {
      setError(err, errLen, "display not found", E_FAIL);
      return false;
    }
    return true;
  }

  bool createDevice(char *err, int errLen) {
    const D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_0, D3D_FEATURE_LEVEL_10_1, D3D_FEATURE_LEVEL_10_0, D3D_FEATURE_LEVEL_9_1};
    HRESULT hr = D3D11CreateDevice(adapter, D3D_DRIVER_TYPE_UNKNOWN, nullptr, 0, levels, ARRAYSIZE(levels),
                                   D3D11_SDK_VERSION, &device, nullptr, &context);
    if (FAILED(hr)) {
      setError(err, errLen, "D3D11CreateDevice failed", hr);
      return false;
    }
    return true;
  }

  // 1 ok, 0 not possible right now (another desktop is shown), -1 error.
  int duplicate(char *err, int errLen) {
    releaseDuplication();
    HRESULT hr = output->DuplicateOutput(device, &dup);
    if (hr == E_ACCESSDENIED || hr == DXGI_ERROR_SESSION_DISCONNECTED || hr == DXGI_ERROR_NOT_CURRENTLY_AVAILABLE) {
      retryAt = GetTickCount() + 250;
      return 0;
    }
    if (FAILED(hr)) {
      setError(err, errLen, "DuplicateOutput failed", hr);
      return -1;
    }
    DXGI_OUTDUPL_DESC desc;
    dup->GetDesc(&desc);
    if (static_cast<int>(desc.ModeDesc.Width) != width || static_cast<int>(desc.ModeDesc.Height) != height) {
      setError(err, errLen, "display size changed", E_FAIL);
      return -1;
    }
    return 1;
  }

  bool ensureStaging(const D3D11_TEXTURE2D_DESC &src, char *err, int errLen) {
    if (staging) return true;
    D3D11_TEXTURE2D_DESC desc = {};
    desc.Width = src.Width;
    desc.Height = src.Height;
    desc.MipLevels = 1;
    desc.ArraySize = 1;
    desc.Format = src.Format;
    desc.SampleDesc.Count = 1;
    desc.Usage = D3D11_USAGE_STAGING;
    desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    HRESULT hr = device->CreateTexture2D(&desc, nullptr, &staging);
    if (FAILED(hr)) {
      setError(err, errLen, "CreateTexture2D failed", hr);
      return false;
    }
    return true;
  }

  void copyOut(const D3D11_MAPPED_SUBRESOURCE &map, uint8_t *dst) {
    const uint8_t *src = static_cast<const uint8_t *>(map.pData);
    if (!halve) {
      const size_t row = static_cast<size_t>(width) * 4;
      for (int y = 0; y < height; y++) memcpy(dst + y * row, src + static_cast<size_t>(y) * map.RowPitch, row);
      return;
    }
    // 2×2 box filter (large screens: a quarter of the data to send and encode).
    const int ow = outWidth(), oh = outHeight();
    for (int y = 0; y < oh; y++) {
      const uint8_t *r0 = src + static_cast<size_t>(2 * y) * map.RowPitch;
      const uint8_t *r1 = r0 + map.RowPitch;
      uint8_t *d = dst + static_cast<size_t>(y) * ow * 4;
      for (int x = 0; x < ow; x++) {
        const uint8_t *a = r0 + x * 8, *b = r1 + x * 8;
        d[0] = static_cast<uint8_t>((a[0] + a[4] + b[0] + b[4] + 2) >> 2);
        d[1] = static_cast<uint8_t>((a[1] + a[5] + b[1] + b[5] + 2) >> 2);
        d[2] = static_cast<uint8_t>((a[2] + a[6] + b[2] + b[6] + 2) >> 2);
        d[3] = 255;
        d += 4;
      }
    }
  }

  int next(int timeoutMs, uint8_t *dst, int dstSize, char *err, int errLen) {
    if (dstSize < outWidth() * outHeight() * 4) {
      setError(err, errLen, "buffer too small", E_INVALIDARG);
      return -1;
    }
    if (!dup) {
      // Lost (desktop switch, full-screen mode change...): try again now and then.
      if (static_cast<LONG>(GetTickCount() - retryAt) < 0) {
        Sleep(timeoutMs > 50 ? 50 : timeoutMs);
        return 0;
      }
      int r = duplicate(err, errLen);
      if (r <= 0) return r;
    }
    if (frameHeld) {
      dup->ReleaseFrame();
      frameHeld = false;
    }
    DXGI_OUTDUPL_FRAME_INFO info;
    IDXGIResource *resource = nullptr;
    HRESULT hr = dup->AcquireNextFrame(timeoutMs < 0 ? 0 : static_cast<UINT>(timeoutMs), &info, &resource);
    if (hr == DXGI_ERROR_WAIT_TIMEOUT) return 0;
    if (hr == DXGI_ERROR_ACCESS_LOST || hr == DXGI_ERROR_INVALID_CALL) {
      releaseDuplication();
      retryAt = GetTickCount();
      return 0;
    }
    if (FAILED(hr)) {
      setError(err, errLen, "AcquireNextFrame failed", hr);
      return -1;
    }
    frameHeld = true;
    // Only the cursor moved (LastPresentTime 0): no new picture.
    if (info.LastPresentTime.QuadPart == 0) {
      resource->Release();
      return 0;
    }
    ID3D11Texture2D *texture = nullptr;
    hr = resource->QueryInterface(__uuidof(ID3D11Texture2D), reinterpret_cast<void **>(&texture));
    resource->Release();
    if (FAILED(hr)) {
      setError(err, errLen, "no texture", hr);
      return -1;
    }
    D3D11_TEXTURE2D_DESC desc;
    texture->GetDesc(&desc);
    if (desc.Format != DXGI_FORMAT_B8G8R8A8_UNORM || static_cast<int>(desc.Width) != width || static_cast<int>(desc.Height) != height) {
      texture->Release();
      setError(err, errLen, "unsupported picture format", E_NOTIMPL);
      return -1;
    }
    if (!ensureStaging(desc, err, errLen)) {
      texture->Release();
      return -1;
    }
    context->CopyResource(staging, texture);
    texture->Release();
    // The copy is made: Windows may update the desktop picture again.
    dup->ReleaseFrame();
    frameHeld = false;
    D3D11_MAPPED_SUBRESOURCE map;
    hr = context->Map(staging, 0, D3D11_MAP_READ, 0, &map);
    if (FAILED(hr)) {
      setError(err, errLen, "Map failed", hr);
      return -1;
    }
    copyOut(map, dst);
    context->Unmap(staging, 0);
    return 1;
  }
};

}  // namespace

extern "C" {

__declspec(dllexport) void *__cdecl pdcOpen(int left, int top, int width, int height, int halve, char *err, int errLen) {
  if (err && errLen > 0) err[0] = 0;
  if (width <= 0 || height <= 0) {
    setError(err, errLen, "bad size", E_INVALIDARG);
    return nullptr;
  }
  Capture *cap = new Capture();
  cap->left = left;
  cap->top = top;
  cap->width = width;
  cap->height = height;
  cap->halve = halve != 0;
  if (!cap->findOutput(err, errLen) || !cap->createDevice(err, errLen) || cap->duplicate(err, errLen) < 0) {
    delete cap;
    return nullptr;
  }
  return cap;
}

__declspec(dllexport) int __cdecl pdcWidth(void *h) { return h ? static_cast<Capture *>(h)->outWidth() : 0; }

__declspec(dllexport) int __cdecl pdcHeight(void *h) { return h ? static_cast<Capture *>(h)->outHeight() : 0; }

__declspec(dllexport) int __cdecl pdcNext(void *h, int timeoutMs, uint8_t *dst, int dstSize, char *err, int errLen) {
  if (err && errLen > 0) err[0] = 0;
  if (!h || !dst) return -1;
  return static_cast<Capture *>(h)->next(timeoutMs, dst, dstSize, err, errLen);
}

__declspec(dllexport) void __cdecl pdcClose(void *h) { delete static_cast<Capture *>(h); }

}  // extern "C"
