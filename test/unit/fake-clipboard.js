// An in-memory stand-in for Electron 44's asynchronous clipboard (tests).

const OS = /^electron application\/osclipboard;format="(.+)"$/;

export class FakeClipboardItem {
  constructor(data) {
    this.data = data;
    this.types = Object.keys(data);
  }

  async getType(type) {
    const v = this.data[type];
    if (v === undefined) throw new Error(`no ${type}`);
    return v instanceof Blob ? v : new Blob([v], { type });
  }
}

export function fakeClipboard(initial = {}) {
  const board = {
    // text/html/rtf strings, png Buffer, raw: { osFormatName: string }
    state: { text: '', html: '', rtf: '', png: null, raw: {}, ...initial },
    writes: [],
    async read() {
      const { text, html, rtf, png, raw } = this.state;
      const data = {};
      if (text) data['text/plain'] = text;
      if (html) data['text/html'] = html;
      if (rtf) data['text/rtf'] = rtf;
      if (png) data['image/png'] = new Blob([png], { type: 'image/png' });
      // A raw OS format written by name reads back under that name.
      for (const [name, value] of Object.entries(raw || {})) data[name] = value;
      return Object.keys(data).length ? [new FakeClipboardItem(data)] : [];
    },
    async readText() {
      return this.state.text;
    },
    async write(items) {
      const item = items[0];
      const state = { text: '', html: '', rtf: '', png: null, raw: {} };
      for (const type of item.types) {
        const blob = await item.getType(type);
        const m = OS.exec(type);
        if (m) state.raw[m[1]] = await blob.text();
        else if (type === 'text/plain') state.text = await blob.text();
        else if (type === 'text/html') state.html = await blob.text();
        else if (type === 'text/rtf') state.rtf = await blob.text();
        else if (type === 'image/png') state.png = Buffer.from(await blob.arrayBuffer());
      }
      this.writes.push(state);
      this.state = state;
    },
  };
  return board;
}
