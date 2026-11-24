// NovaTerm VT100/ANSI Terminal Emulator
// Handles: SGR colors, cursor movement, erase, scrolling regions, alternate screen,
//          line insert/delete, character insert/delete, most common escape sequences.

(function(global) {
'use strict';

const DEFAULT_FG = (typeof window.__TERM_FG__ !== 'undefined') ? window.__TERM_FG__ : '#cdd6f4';
const DEFAULT_BG = (typeof window.__TERM_BG__ !== 'undefined') ? window.__TERM_BG__ : '#1e1e2e';
const COLS = 220, ROWS = 50;

// ANSI 256-color palette
const PALETTE = (() => {
  const p = [];
  // Standard 16
  const base = ['#1e1e2e','#f38ba8','#a6e3a1','#f9e2af','#89b4fa','#cba6f7','#89dceb','#cdd6f4',
                 '#585b70','#f38ba8','#a6e3a1','#f9e2af','#89b4fa','#cba6f7','#89dceb','#a6adc8'];
  for (let i = 0; i < 16; i++) p.push(base[i]);
  // 6×6×6 cube
  for (let r = 0; r < 6; r++) for (let g = 0; g < 6; g++) for (let b = 0; b < 6; b++) {
    const v = i => i === 0 ? 0 : 55 + i * 40;
    p.push(`rgb(${v(r)},${v(g)},${v(b)})`);
  }
  // Grayscale
  for (let i = 0; i < 24; i++) { const v = 8 + i * 10; p.push(`rgb(${v},${v},${v})`); }
  return p;
})();

function Cell(ch, fg, bg, bold, italic, underline, inverse) {
  return { ch: ch || ' ', fg: fg || DEFAULT_FG, bg: bg || DEFAULT_BG,
           bold: bold || false, italic: italic || false,
           underline: underline || false, inverse: inverse || false };
}

function emptyRow(cols) {
  const r = new Array(cols);
  for (let i = 0; i < cols; i++) r[i] = Cell();
  return r;
}

class Terminal {
  constructor(canvas, cols, rows) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.cols = cols;
    this.rows = rows;
    this.fontSize = (typeof window.__TERM_FONT_SIZE__ !== 'undefined') ? window.__TERM_FONT_SIZE__ : 13;
    var ff = (typeof window.__TERM_FONT_FAMILY__ !== 'undefined') ? window.__TERM_FONT_FAMILY__ : 'SF Mono';
    this.fontFamily = "'" + ff + "', Menlo, 'Courier New', monospace";
    this._measureCell();

    // Screen buffers
    this.screen = Array.from({length: rows}, () => emptyRow(cols));
    this.altScreen = null;
    this.savedCursor = null;

    // Cursor
    this.cx = 0; this.cy = 0;
    this.cursorVisible = true;
    this.cursorBlink = true;

    // Attributes
    this.fg = DEFAULT_FG; this.bg = DEFAULT_BG;
    this.bold = false; this.italic = false;
    this.underline = false; this.inverse = false;

    // Scroll region
    this.scrollTop = 0; this.scrollBottom = rows - 1;

    // Parse state
    this.parseState = 'normal';
    this.escBuf = '';

    // Charset / modes
    this.insertMode = false;
    this.autoWrap = true;
    this.pendingWrap = false;

    // Scrollback buffer
    this.maxScrollback = (typeof window.__TERM_SCROLLBACK__ !== 'undefined') ? window.__TERM_SCROLLBACK__ : 10000;
    this.scrollback = []; // rows pushed off screen top
    this.viewOffset = 0;  // 0 = live view; >0 = scrolled back N rows

    // Mouse reporting modes: 0=off, 1000=normal, 1002=button, 1003=all
    this.mouseMode = 0;
    this.mouseSGR = false; // ESC[?1006h: SGR extended mouse encoding
    this.bracketedPaste = false; // ESC[?2004h: wrap paste in ESC[200~/ESC[201~

    this._rafPending = false;
    this._blinkTimer = setInterval(() => this._blink(), 500);
    this._blinkOn = true;
    this.render();
  }

  _measureCell() {
    const ctx = this.canvas.getContext('2d');
    ctx.font = `${this.fontSize}px ${this.fontFamily}`;
    this.cw = Math.ceil(ctx.measureText('M').width);
    this.ch = Math.ceil(this.fontSize * 1.5);
    this.canvas.width = this.cols * this.cw;
    this.canvas.height = this.rows * this.ch;
  }

  _blink() {
    if (!this.cursorVisible || !this.cursorBlink) return;
    this._blinkOn = !this._blinkOn;
    this._renderCursor();
  }

  resize(cols, rows) {
    this.cols = cols; this.rows = rows;
    this._measureCell();
    // Resize screen buffer
    while (this.screen.length < rows) this.screen.push(emptyRow(cols));
    this.screen = this.screen.slice(0, rows);
    this.screen.forEach(row => {
      while (row.length < cols) row.push(Cell());
      row.length = cols;
    });
    this.scrollBottom = rows - 1;
    this.cx = Math.min(this.cx, cols - 1);
    this.cy = Math.min(this.cy, rows - 1);
    this.render();
  }

  write(data) {
    for (let i = 0; i < data.length; i++) {
      this._processChar(data[i], data.charCodeAt(i));
    }
    this._scheduleRender();
  }

  _scheduleRender() {
    if (this._rafPending) return;
    this._rafPending = true;
    requestAnimationFrame(() => { this._rafPending = false; this.render(); });
  }

  _processChar(ch, code) {
    if (this.parseState === 'escape') {
      this.parseState = 'normal';
      switch (ch) {
        case '[': this.parseState = 'csi'; this.escBuf = ''; return;
        case ']': this.parseState = 'osc'; this.escBuf = ''; return;
        case '(': this.parseState = 'charset'; return;
        case 'M': this._reverseIndex(); return;
        case '7': this._saveCursor(); return;
        case '8': this._restoreCursor(); return;
        case 'D': this._lineFeed(); return;
        case 'E': this.cx = 0; this._lineFeed(); return;
        case '=': case '>': return; // keypad modes
        default: return;
      }
    }
    if (this.parseState === 'charset') { this.parseState = 'normal'; return; }
    if (this.parseState === 'csi') {
      if (ch >= '@' && ch <= '~') {
        this._handleCSI(this.escBuf, ch);
        this.parseState = 'normal'; this.escBuf = '';
      } else {
        this.escBuf += ch;
      }
      return;
    }
    if (this.parseState === 'osc') {
      if (ch === '\x07' || (ch === '\\' && this.escBuf.endsWith('\x1b'))) {
        // OSC terminated: parse ps;data
        const oscBuf = this.escBuf.replace(/\x1b$/, '');
        const semi = oscBuf.indexOf(';');
        if (semi !== -1) {
          const ps = parseInt(oscBuf.slice(0, semi));
          const data = oscBuf.slice(semi + 1);
          if ((ps === 0 || ps === 2) && data) {
            if (window.javaBridge && window.javaBridge.setTitle) {
              window.javaBridge.setTitle(data);
            }
          } else if (ps === 7 && data) {
            // OSC 7: current directory URI (file://host/path)
            try {
              var url = new URL(data);
              var dir = decodeURIComponent(url.pathname);
              window.__TERM_CWD__ = dir;
              if (window.javaBridge) {
                if (window.javaBridge.setDirectory) window.javaBridge.setDirectory(dir);
                if (window.javaBridge.setTitle) window.javaBridge.setTitle(dir.split('/').pop() || dir);
              }
            } catch(ignore) {}
          } else if (ps === 1337 && data.startsWith('CurrentDir=')) {
            // iTerm2-style CurrentDir
            var iDir = data.slice(11);
            window.__TERM_CWD__ = iDir;
            if (window.javaBridge && window.javaBridge.setDirectory) window.javaBridge.setDirectory(iDir);
          }
        }
        this.parseState = 'normal'; this.escBuf = '';
      } else {
        this.escBuf += ch;
      }
      return;
    }
    // Normal
    switch (code) {
      case 0x1b: this.parseState = 'escape'; return;
      case 0x0d: this.cx = 0; this.pendingWrap = false; return;
      case 0x0a: case 0x0b: case 0x0c: this._lineFeed(); return;
      case 0x08: if (this.cx > 0) { this.cx--; this.pendingWrap = false; } return;
      case 0x09: this.cx = Math.min(this.cols - 1, Math.floor((this.cx + 8) / 8) * 8); return;
      case 0x07:
        // Visual bell: flash the canvas briefly
        this._visualBell();
        if (window.javaBridge && window.javaBridge.bell) window.javaBridge.bell();
        return;
    }
    if (code < 0x20) return;

    if (this.pendingWrap) {
      this.cx = 0;
      this._lineFeed();
      this.pendingWrap = false;
    }
    if (this.insertMode) {
      const row = this.screen[this.cy];
      row.splice(this.cx, 0, Cell(ch, this.fg, this.bg, this.bold, this.italic, this.underline, this.inverse));
      row.length = this.cols;
    } else {
      this.screen[this.cy][this.cx] = Cell(ch, this.fg, this.bg, this.bold, this.italic, this.underline, this.inverse);
    }
    if (this.cx < this.cols - 1) {
      this.cx++;
    } else if (this.autoWrap) {
      this.pendingWrap = true;
    }
  }

  _lineFeed() {
    this.pendingWrap = false;
    if (this.cy === this.scrollBottom) {
      this._scrollUp(1);
    } else if (this.cy < this.rows - 1) {
      this.cy++;
    }
  }

  _reverseIndex() {
    if (this.cy === this.scrollTop) {
      this._scrollDown(1);
    } else if (this.cy > 0) {
      this.cy--;
    }
  }

  _scrollUp(n) {
    for (let i = 0; i < n; i++) {
      const evicted = this.screen.splice(this.scrollTop, 1)[0];
      // Push to scrollback only when scrolling the full-screen region
      if (this.scrollTop === 0 && this.altScreen === null) {
        this.scrollback.push(evicted);
        if (this.scrollback.length > this.maxScrollback)
          this.scrollback.shift();
      }
      this.screen.splice(this.scrollBottom, 0, emptyRow(this.cols));
    }
  }

  // Scroll back/forward in scrollback buffer without affecting the live screen
  scrollBackBy(lines) {
    this.viewOffset = Math.min(this.scrollback.length,
                               Math.max(0, this.viewOffset + lines));
    this.render();
  }

  scrollToBottom() {
    if (this.viewOffset !== 0) { this.viewOffset = 0; this.render(); }
  }

  _scrollDown(n) {
    for (let i = 0; i < n; i++) {
      this.screen.splice(this.scrollBottom, 1);
      this.screen.splice(this.scrollTop, 0, emptyRow(this.cols));
    }
  }

  _saveCursor() {
    this.savedCursor = { cx: this.cx, cy: this.cy,
      fg: this.fg, bg: this.bg, bold: this.bold,
      italic: this.italic, underline: this.underline };
  }

  _restoreCursor() {
    if (!this.savedCursor) return;
    Object.assign(this, this.savedCursor);
  }

  _handleCSI(buf, final) {
    const priv = buf.startsWith('?');
    const params = (priv ? buf.slice(1) : buf).split(';').map(s => parseInt(s) || 0);
    const p = (i, def) => (params[i] !== undefined && params[i] !== 0 ? params[i] : def);

    if (priv) {
      switch (final) {
        case 'h':
          if (params[0] === 1049) this._enterAltScreen();
          if (params[0] === 1047) this._enterAltScreen();
          if (params[0] === 1048) this._saveCursor();
          if (params[0] === 1) { /* app cursor keys */ }
          if (params[0] === 12) this.cursorBlink = true;
          if (params[0] === 25) this.cursorVisible = true;
          if (params[0] === 7) this.autoWrap = true;
          if (params[0] === 4) this.insertMode = true;
          if (params[0] === 2004) this.bracketedPaste = true;
          if (params[0] === 1000 || params[0] === 1002 || params[0] === 1003) this.mouseMode = params[0];
          if (params[0] === 1006) this.mouseSGR = true;
          return;
        case 'l':
          if (params[0] === 1049) this._exitAltScreen();
          if (params[0] === 1047) this._exitAltScreen();
          if (params[0] === 1048) this._restoreCursor();
          if (params[0] === 12) this.cursorBlink = false;
          if (params[0] === 25) this.cursorVisible = false;
          if (params[0] === 7) this.autoWrap = false;
          if (params[0] === 4) this.insertMode = false;
          if (params[0] === 2004) this.bracketedPaste = false;
          if (params[0] === 1000 || params[0] === 1002 || params[0] === 1003) this.mouseMode = 0;
          if (params[0] === 1006) this.mouseSGR = false;
          return;
      }
      return;
    }

    switch (final) {
      case 'A': this.cy = Math.max(this.scrollTop, this.cy - p(0, 1)); this.pendingWrap = false; break;
      case 'B': this.cy = Math.min(this.scrollBottom, this.cy + p(0, 1)); this.pendingWrap = false; break;
      case 'C': this.cx = Math.min(this.cols - 1, this.cx + p(0, 1)); this.pendingWrap = false; break;
      case 'D': this.cx = Math.max(0, this.cx - p(0, 1)); this.pendingWrap = false; break;
      case 'E': this.cx = 0; this.cy = Math.min(this.rows - 1, this.cy + p(0, 1)); break;
      case 'F': this.cx = 0; this.cy = Math.max(0, this.cy - p(0, 1)); break;
      case 'G': this.cx = Math.min(this.cols - 1, Math.max(0, p(0, 1) - 1)); break;
      case 'H': case 'f':
        this.cy = Math.min(this.rows - 1, Math.max(0, p(0, 1) - 1));
        this.cx = Math.min(this.cols - 1, Math.max(0, p(1, 1) - 1));
        this.pendingWrap = false;
        break;
      case 'J': this._eraseDisplay(params[0]); break;
      case 'K': this._eraseLine(params[0]); break;
      case 'L': this._insertLines(p(0, 1)); break;
      case 'M': this._deleteLines(p(0, 1)); break;
      case 'P': this._deleteChars(p(0, 1)); break;
      case '@': this._insertChars(p(0, 1)); break;
      case 'S': this._scrollUp(p(0, 1)); break;
      case 'T': this._scrollDown(p(0, 1)); break;
      case 'X': this._eraseChars(p(0, 1)); break;
      case 'd': this.cy = Math.min(this.rows - 1, Math.max(0, p(0, 1) - 1)); break;
      case 'm': this._handleSGR(params); break;
      case 'r':
        this.scrollTop = Math.max(0, p(0, 1) - 1);
        this.scrollBottom = Math.min(this.rows - 1, p(1, this.rows) - 1);
        this.cx = 0; this.cy = 0;
        break;
      case 's': this._saveCursor(); break;
      case 'u': this._restoreCursor(); break;
      case 'h': if (params[0] === 4) this.insertMode = true; break;
      case 'l': if (params[0] === 4) this.insertMode = false; break;
      case 'n':
        if (params[0] === 6) {
          // DSR cursor position report: ESC[row;colR
          const rpt = `\x1b[${this.cy + 1};${this.cx + 1}R`;
          if (window.javaBridge) window.javaBridge.sendData(rpt);
        }
        break;
      case 'c':
        // Send terminal ID: VT220
        if (window.javaBridge) window.javaBridge.sendData('\x1b[?62;1;2;6;9;15;22c');
        break;
    }
  }

  _eraseDisplay(mode) {
    const clear = () => emptyRow(this.cols);
    if (mode === 0) {
      this.screen[this.cy].fill(Cell(), this.cx);
      for (let r = this.cy + 1; r < this.rows; r++) this.screen[r] = clear();
    } else if (mode === 1) {
      this.screen[this.cy].fill(Cell(), 0, this.cx + 1);
      for (let r = 0; r < this.cy; r++) this.screen[r] = clear();
    } else if (mode === 2 || mode === 3) {
      for (let r = 0; r < this.rows; r++) this.screen[r] = clear();
      if (mode === 2) { this.cx = 0; this.cy = 0; }
    }
  }

  _eraseLine(mode) {
    const row = this.screen[this.cy];
    if (mode === 0) for (let c = this.cx; c < this.cols; c++) row[c] = Cell();
    else if (mode === 1) for (let c = 0; c <= this.cx; c++) row[c] = Cell();
    else if (mode === 2) for (let c = 0; c < this.cols; c++) row[c] = Cell();
  }

  _eraseChars(n) {
    for (let i = 0; i < n; i++) {
      if (this.cx + i < this.cols) this.screen[this.cy][this.cx + i] = Cell();
    }
  }

  _insertLines(n) {
    for (let i = 0; i < n; i++) {
      this.screen.splice(this.scrollBottom, 1);
      this.screen.splice(this.cy, 0, emptyRow(this.cols));
    }
  }

  _deleteLines(n) {
    for (let i = 0; i < n; i++) {
      this.screen.splice(this.cy, 1);
      this.screen.splice(this.scrollBottom, 0, emptyRow(this.cols));
    }
  }

  _deleteChars(n) {
    const row = this.screen[this.cy];
    row.splice(this.cx, n);
    while (row.length < this.cols) row.push(Cell());
  }

  _insertChars(n) {
    const row = this.screen[this.cy];
    for (let i = 0; i < n; i++) row.splice(this.cx, 0, Cell());
    row.length = this.cols;
  }

  _handleSGR(params) {
    if (params.length === 0) params = [0];
    let i = 0;
    while (i < params.length) {
      const p = params[i];
      if (p === 0) {
        this.fg = DEFAULT_FG; this.bg = DEFAULT_BG;
        this.bold = this.italic = this.underline = this.inverse = false;
      } else if (p === 1) this.bold = true;
      else if (p === 3) this.italic = true;
      else if (p === 4) this.underline = true;
      else if (p === 7) this.inverse = true;
      else if (p === 22) this.bold = false;
      else if (p === 23) this.italic = false;
      else if (p === 24) this.underline = false;
      else if (p === 27) this.inverse = false;
      else if (p >= 30 && p <= 37) this.fg = PALETTE[p - 30];
      else if (p === 38) {
        if (params[i+1] === 5 && i+2 < params.length) { this.fg = PALETTE[params[i+2]]; i += 2; }
        else if (params[i+1] === 2 && i+4 < params.length) { this.fg = `rgb(${params[i+2]},${params[i+3]},${params[i+4]})`; i += 4; }
      }
      else if (p === 39) this.fg = DEFAULT_FG;
      else if (p >= 40 && p <= 47) this.bg = PALETTE[p - 40];
      else if (p === 48) {
        if (params[i+1] === 5 && i+2 < params.length) { this.bg = PALETTE[params[i+2]]; i += 2; }
        else if (params[i+1] === 2 && i+4 < params.length) { this.bg = `rgb(${params[i+2]},${params[i+3]},${params[i+4]})`; i += 4; }
      }
      else if (p === 49) this.bg = DEFAULT_BG;
      else if (p >= 90 && p <= 97) this.fg = PALETTE[p - 90 + 8];
      else if (p >= 100 && p <= 107) this.bg = PALETTE[p - 100 + 8];
      i++;
    }
  }

  _enterAltScreen() {
    if (this.altScreen) return;
    this.altScreen = { screen: this.screen, cx: this.cx, cy: this.cy };
    this.screen = Array.from({length: this.rows}, () => emptyRow(this.cols));
    this.cx = 0; this.cy = 0;
    this.scrollTop = 0; this.scrollBottom = this.rows - 1;
  }

  _exitAltScreen() {
    if (!this.altScreen) return;
    this.screen = this.altScreen.screen;
    this.cx = this.altScreen.cx;
    this.cy = this.altScreen.cy;
    this.altScreen = null;
    this.scrollTop = 0; this.scrollBottom = this.rows - 1;
    this.render();
  }

  render() {
    const ctx = this.ctx;
    ctx.font = `${this.bold ? 'bold ' : ''}${this.fontSize}px ${this.fontFamily}`;

    for (let r = 0; r < this.rows; r++) {
      // When scrolled back, combine scrollback + screen rows
      let cell;
      if (this.viewOffset > 0) {
        const sbLen = this.scrollback.length;
        const startSb = sbLen - this.viewOffset;
        const sbRow = startSb + r;
        const row = sbRow < sbLen ? this.scrollback[sbRow] : this.screen[r - (sbLen - startSb)];
        cell = row ? row[0] : null; // Just for block below — need per-cell
        for (let c = 0; c < this.cols; c++) {
          const viewRow = sbRow < sbLen ? this.scrollback[sbRow] : this.screen[r - (sbLen - startSb)];
          cell = viewRow ? viewRow[c] : Cell();
          if (!cell) cell = Cell();
          const fg = cell.inverse ? cell.bg : cell.fg;
          const bg = cell.inverse ? cell.fg : cell.bg;
          ctx.fillStyle = bg;
          ctx.fillRect(c * this.cw, r * this.ch, this.cw, this.ch);
          if (cell.ch !== ' ') {
            ctx.font = `${cell.bold ? 'bold ' : ''}${cell.italic ? 'italic ' : ''}${this.fontSize}px ${this.fontFamily}`;
            ctx.fillStyle = fg;
            ctx.fillText(cell.ch, c * this.cw, r * this.ch + this.ch - 3);
          }
        }
        continue;
      }
      for (let c = 0; c < this.cols; c++) {
        cell = this.screen[r][c];
        const fg = cell.inverse ? cell.bg : cell.fg;
        const bg = cell.inverse ? cell.fg : cell.bg;

        ctx.fillStyle = bg;
        ctx.fillRect(c * this.cw, r * this.ch, this.cw, this.ch);

        if (cell.ch !== ' ') {
          ctx.font = `${cell.bold ? 'bold ' : ''}${cell.italic ? 'italic ' : ''}${this.fontSize}px ${this.fontFamily}`;
          ctx.fillStyle = fg;
          ctx.fillText(cell.ch, c * this.cw, r * this.ch + this.ch - 3);
        }
        if (cell.underline) {
          ctx.fillStyle = fg;
          ctx.fillRect(c * this.cw, r * this.ch + this.ch - 2, this.cw, 1);
        }
      }
    }
    this._renderFindHighlights();
    this._buildUrlRegions();
    this._renderUrlUnderlines();
    this._renderSelection();
    this._renderCursor();
  }

  _renderCursor() {
    if (!this.cursorVisible) return;
    const ctx = this.ctx;
    const x = this.cx * this.cw;
    const y = this.cy * this.ch;
    const style = (typeof window.__TERM_CURSOR_STYLE__ !== 'undefined') ? window.__TERM_CURSOR_STYLE__ : 'block';
    if (this._blinkOn) {
      if (style === 'underline') {
        ctx.fillStyle = DEFAULT_FG;
        ctx.globalAlpha = 0.9;
        ctx.fillRect(x, y + this.ch - 2, this.cw, 2);
        ctx.globalAlpha = 1.0;
      } else if (style === 'beam') {
        ctx.fillStyle = DEFAULT_FG;
        ctx.globalAlpha = 0.9;
        ctx.fillRect(x, y, 2, this.ch);
        ctx.globalAlpha = 1.0;
      } else {
        // block (default)
        ctx.fillStyle = DEFAULT_FG;
        ctx.globalAlpha = 0.7;
        ctx.fillRect(x, y, this.cw, this.ch);
        ctx.globalAlpha = 1.0;
        const cell = this.screen[this.cy][this.cx];
        ctx.font = `${cell.bold ? 'bold ' : ''}${this.fontSize}px ${this.fontFamily}`;
        ctx.fillStyle = DEFAULT_BG;
        ctx.fillText(cell.ch, x, y + this.ch - 3);
      }
    } else {
      // Redraw cell without cursor
      const cell = this.screen[this.cy][this.cx];
      ctx.fillStyle = cell.bg;
      ctx.fillRect(x, y, this.cw, this.ch);
      if (cell.ch !== ' ') {
        ctx.font = `${cell.bold ? 'bold ' : ''}${this.fontSize}px ${this.fontFamily}`;
        ctx.fillStyle = cell.fg;
        ctx.fillText(cell.ch, x, y + this.ch - 3);
      }
    }
  }

  // ── Selection ──────────────────────────────────────────────────────────────

  startSelection(col, row) {
    this.sel = { startCol: col, startRow: row, endCol: col, endRow: row, active: true };
  }

  updateSelection(col, row) {
    if (!this.sel || !this.sel.active) return;
    this.sel.endCol = col;
    this.sel.endRow = row;
    this.render();
  }

  endSelection() {
    if (this.sel) this.sel.active = false;
  }

  getSelectedText() {
    if (!this.sel) return '';
    let { startRow, startCol, endRow, endCol } = this.sel;
    if (startRow > endRow || (startRow === endRow && startCol > endCol)) {
      [startRow, endRow] = [endRow, startRow];
      [startCol, endCol] = [endCol, startCol];
    }
    let text = '';
    for (let r = startRow; r <= endRow; r++) {
      const from = r === startRow ? startCol : 0;
      const to   = r === endRow   ? endCol   : this.cols - 1;
      let line = '';
      for (let c = from; c <= to; c++) line += this.screen[r][c].ch;
      text += line.trimEnd();
      if (r < endRow) text += '\n';
    }
    return text;
  }

  clearSelection() {
    this.sel = null;
    this.render();
  }

  _renderSelection() {
    if (!this.sel) return;
    const ctx = this.ctx;
    let { startRow, startCol, endRow, endCol } = this.sel;
    if (startRow > endRow || (startRow === endRow && startCol > endCol)) {
      [startRow, endRow] = [endRow, startRow];
      [startCol, endCol] = [endCol, startCol];
    }
    ctx.fillStyle = 'rgba(137,180,250,0.3)';
    for (let r = startRow; r <= endRow; r++) {
      const from = r === startRow ? startCol : 0;
      const to   = r === endRow   ? endCol   : this.cols - 1;
      ctx.fillRect(from * this.cw, r * this.ch, (to - from + 1) * this.cw, this.ch);
    }
  }

  // ── Mouse reporting ────────────────────────────────────────────────────────

  cellFromPixel(x, y) {
    return {
      col: Math.min(this.cols - 1, Math.max(0, Math.floor(x / this.cw))),
      row: Math.min(this.rows - 1, Math.max(0, Math.floor(y / this.ch)))
    };
  }

  // Returns the escape sequence to send for a mouse event, or '' if mouse mode is off.
  // btn: 0=left, 1=mid, 2=right; press: true=down, false=up
  mouseReport(btn, col, row, press) {
    if (this.mouseMode === 0) return '';
    const b = press ? btn : 3; // X10: button 3 = release
    if (this.mouseSGR) {
      // ESC[<b;x;yM (press) / ESC[<b;x;ym (release)
      const fin = press ? 'M' : 'm';
      return `\x1b[<${b};${col + 1};${row + 1}${fin}`;
    }
    // X10 encoding: clamp to 223 (byte limit)
    const bc = Math.min(b + 32, 255);
    const xc = Math.min(col + 1 + 32, 255);
    const yc = Math.min(row + 1 + 32, 255);
    return `\x1b[M${String.fromCharCode(bc)}${String.fromCharCode(xc)}${String.fromCharCode(yc)}`;
  }

  // ── Find / highlight ──────────────────────────────────────────────────────

  findText(query) {
    this.findMatches = [];
    this.findCurrent = -1;
    this.findQuery = query;
    if (!query) { this.render(); return 0; }
    const q = query.toLowerCase();
    const allRows = this.scrollback.concat(this.screen);
    for (let r = 0; r < allRows.length; r++) {
      const line = allRows[r].map(c => c.ch).join('');
      let pos = 0;
      while ((pos = line.toLowerCase().indexOf(q, pos)) !== -1) {
        this.findMatches.push({ row: r, col: pos, len: q.length });
        pos += q.length;
      }
    }
    if (this.findMatches.length > 0) { this.findCurrent = 0; this._scrollToMatch(0); }
    this.render();
    return this.findMatches.length;
  }

  findNext() {
    if (!this.findMatches || this.findMatches.length === 0) return;
    this.findCurrent = (this.findCurrent + 1) % this.findMatches.length;
    this._scrollToMatch(this.findCurrent);
    this.render();
    return this.findCurrent;
  }

  findPrev() {
    if (!this.findMatches || this.findMatches.length === 0) return;
    this.findCurrent = (this.findCurrent - 1 + this.findMatches.length) % this.findMatches.length;
    this._scrollToMatch(this.findCurrent);
    this.render();
    return this.findCurrent;
  }

  _scrollToMatch(idx) {
    const m = this.findMatches[idx];
    if (!m) return;
    const sbLen = this.scrollback.length;
    if (m.row < sbLen) {
      // Scrollback row: set viewOffset so match is visible near center
      const desiredOffset = Math.max(0, sbLen - m.row - Math.floor(this.rows / 2));
      this.viewOffset = Math.min(desiredOffset, sbLen);
    } else {
      this.viewOffset = 0; // snap back to live view if on screen
    }
  }

  clearFind() {
    this.findMatches = [];
    this.findCurrent = -1;
    this.findQuery = '';
    this.render();
  }

  // Paste text as if typed — sends each char through the input bridge
  pasteText(text) {
    if (window.javaBridge) window.javaBridge.sendData(text);
  }

  _renderFindHighlights() {
    if (!this.findMatches || this.findMatches.length === 0) return;
    const ctx = this.ctx;
    const sbLen = this.scrollback.length;
    for (let i = 0; i < this.findMatches.length; i++) {
      const m = this.findMatches[i];
      const isCurrent = i === this.findCurrent;
      let visRow;
      if (m.row < sbLen) {
        const sbVisIdx = m.row - (sbLen - this.viewOffset);
        if (sbVisIdx < 0 || sbVisIdx >= this.rows) continue;
        visRow = sbVisIdx;
      } else {
        const screenRow = m.row - sbLen;
        if (this.viewOffset > 0) continue;
        visRow = screenRow;
      }
      ctx.fillStyle = isCurrent ? 'rgba(166,227,161,0.7)' : 'rgba(249,226,175,0.45)';
      ctx.fillRect(m.col * this.cw, visRow * this.ch, m.len * this.cw, this.ch);
    }
  }

  /** Return all scrollback + on-screen content as a plain-text string (for saving to file). */
  getScrollbackText() {
    const rows = this.scrollback.concat(this.screen);
    return rows.map(row => row.map(c => c.ch).join('').trimEnd()).join('\n');
  }

  // ── URL hover / hyperlink overlay ─────────────────────────────────────────

  _detectUrls(row, text) {
    const re = /https?:\/\/[^\s\x00-\x1f"'<>()[\]{}\\,;]{3,}/g;
    let m;
    const urls = [];
    while ((m = re.exec(text)) !== null) urls.push({ col: m.index, len: m[0].length, url: m[0] });
    return urls;
  }

  _renderUrlUnderlines() {
    if (!this._urlRegions) return;
    const ctx = this.ctx;
    ctx.strokeStyle = '#89b4fa';
    ctx.lineWidth = 1;
    for (const r of this._urlRegions) {
      const y = r.visRow * this.ch + this.ch - 1;
      ctx.beginPath();
      ctx.moveTo(r.col * this.cw, y);
      ctx.lineTo((r.col + r.len) * this.cw, y);
      ctx.stroke();
    }
  }

  _buildUrlRegions() {
    this._urlRegions = [];
    const sbLen = this.scrollback.length;
    const startSbRow = sbLen - this.viewOffset;
    for (let visRow = 0; visRow < this.rows; visRow++) {
      let row;
      if (this.viewOffset > 0) {
        const sbIdx = startSbRow + visRow;
        if (sbIdx < 0 || sbIdx >= sbLen) continue;
        row = this.scrollback[sbIdx];
      } else {
        row = this.screen[visRow];
      }
      if (!row) continue;
      const text = row.map(c => c.ch).join('');
      const urls = this._detectUrls(0, text);
      for (const u of urls) {
        this._urlRegions.push({ visRow, col: u.col, len: u.len, url: u.url });
      }
    }
  }

  urlAtPixel(px, py) {
    if (!this._urlRegions) return null;
    const col = Math.floor(px / this.cw);
    const row = Math.floor(py / this.ch);
    for (const r of this._urlRegions) {
      if (r.visRow === row && col >= r.col && col < r.col + r.len) return r.url;
    }
    return null;
  }

  _visualBell() {
    const ctx = this.ctx;
    const w = this.canvas.width, h = this.canvas.height;
    ctx.save();
    ctx.fillStyle = 'rgba(249,226,175,0.35)';
    ctx.fillRect(0, 0, w, h);
    ctx.restore();
    setTimeout(() => this.render(), 120);
  }

  destroy() { clearInterval(this._blinkTimer); }
}

global.VT100Terminal = Terminal;
})(window);
