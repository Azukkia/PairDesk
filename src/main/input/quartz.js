// macOS system functions used by the input helper (input injection, cursor
// shape), declared once per process with koffi. All of them are public APIs
// of CoreGraphics (Quartz Event Services), CoreFoundation, IOKit and Carbon.

import koffi from 'koffi';

const FRAMEWORKS = '/System/Library/Frameworks';
let quartz = null;

export function loadQuartz() {
  if (quartz) return quartz;
  const cg = koffi.load(`${FRAMEWORKS}/CoreGraphics.framework/CoreGraphics`);
  const cf = koffi.load(`${FRAMEWORKS}/CoreFoundation.framework/CoreFoundation`);
  const iokit = koffi.load(`${FRAMEWORKS}/IOKit.framework/IOKit`);
  // CGFloat is a double on every 64-bit Mac. CGRect = {CGPoint origin; CGSize size}.
  koffi.struct('PD_CGPoint', { x: 'double', y: 'double' });
  koffi.struct('PD_CGRect', { x: 'double', y: 'double', width: 'double', height: 'double' });
  quartz = {
    // Events (CGEventRef: released with CFRelease)
    CGEventCreate: cg.func('void *CGEventCreate(void *source)'),
    CGEventCreateMouseEvent: cg.func('void *CGEventCreateMouseEvent(void *source, uint32_t type, PD_CGPoint position, uint32_t button)'),
    CGEventCreateKeyboardEvent: cg.func('void *CGEventCreateKeyboardEvent(void *source, uint16_t key, bool down)'),
    // The fixed-arity variant of the variadic CGEventCreateScrollWheelEvent: on
    // Apple silicon variadic arguments are passed differently (on the stack).
    CGEventCreateScrollWheelEvent2: cg.func(
      'void *CGEventCreateScrollWheelEvent2(void *source, uint32_t units, uint32_t count, int32_t wheel1, int32_t wheel2, int32_t wheel3)',
    ),
    CGEventSetIntegerValueField: cg.func('void CGEventSetIntegerValueField(void *event, uint32_t field, int64_t value)'),
    CGEventSetFlags: cg.func('void CGEventSetFlags(void *event, uint64_t flags)'),
    CGEventKeyboardSetUnicodeString: cg.func('void CGEventKeyboardSetUnicodeString(void *event, unsigned long length, const uint16_t *chars)'),
    CGEventGetLocation: cg.func('PD_CGPoint CGEventGetLocation(void *event)'),
    CGEventPost: cg.func('void CGEventPost(uint32_t tap, void *event)'),
    // Bitmaps (cursor images)
    CGImageGetWidth: cg.func('size_t CGImageGetWidth(void *image)'),
    CGImageGetHeight: cg.func('size_t CGImageGetHeight(void *image)'),
    CGColorSpaceCreateDeviceRGB: cg.func('void *CGColorSpaceCreateDeviceRGB()'),
    CGBitmapContextCreate: cg.func(
      'void *CGBitmapContextCreate(void *data, size_t width, size_t height, size_t bitsPerComponent, size_t bytesPerRow, void *space, uint32_t info)',
    ),
    CGBitmapContextGetData: cg.func('void *CGBitmapContextGetData(void *context)'),
    CGContextDrawImage: cg.func('void CGContextDrawImage(void *context, PD_CGRect rect, void *image)'),
    // Core Foundation
    CFRelease: cf.func('void CFRelease(void *object)'),
    CFStringCreateWithCString: cf.func('void *CFStringCreateWithCString(void *allocator, const char *text, uint32_t encoding)'),
    // Power management: "the user is active" turns the display back on.
    IOPMAssertionDeclareUserActivity: iokit.func('int IOPMAssertionDeclareUserActivity(void *name, int type, _Inout_ uint32_t *id)'),
  };
  return quartz;
}

/**
 * Physical layout of the Mac's keyboard: 'ISO', 'ANSI', 'JIS' or null when
 * unknown. On ISO keyboards macOS swaps the codes of the key left of "1" and
 * the key left of "Z" (kVK_ISO_Section / kVK_ANSI_Grave).
 */
export function keyboardLayoutType() {
  try {
    const carbon = koffi.load(`${FRAMEWORKS}/Carbon.framework/Carbon`);
    const LMGetKbdType = carbon.func('uint8_t LMGetKbdType()');
    const KBGetLayoutType = carbon.func('uint32_t KBGetLayoutType(int16_t keyboardType)');
    const type = KBGetLayoutType(LMGetKbdType());
    return { 0x49534f20: 'ISO', 0x414e5349: 'ANSI', 0x4a495320: 'JIS' }[type] ?? null;
  } catch {
    return null;
  }
}
