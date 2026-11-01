// Stockfish в Web Worker. Запросы идут строго по одному: движок один, а тренер
// и сканирование партии могут спрашивать его одновременно.
export class Engine {
  constructor(url) {
    this.url = url;
    this.tail = Promise.resolve();
    this.worker = null;
    this.listener = null;
    this.ready = null;
  }

  start() {
    if (this.ready) return this.ready;
    this.worker = new Worker(this.url);
    this.worker.onmessage = (event) => {
      const line = typeof event.data === "string" ? event.data : String(event.data);
      if (this.listener) this.listener(line);
    };
    this.ready = this.waitFor("uciok", "uci").then(() => this.waitFor("readyok", "isready"));
    return this.ready;
  }

  waitFor(answer, command) {
    return new Promise((resolve, reject) => {
      this.worker.onerror = (event) => reject(new Error(event.message || "движок не запустился"));
      this.listener = (line) => {
        if (line.startsWith(answer)) resolve();
      };
      this.worker.postMessage(command);
    });
  }

  /** Строки info с итоговым вариантом каждого multipv, через "|", лучшая первой. */
  analyse(fen, movetimeMs, depth, multiPv) {
    const run = () => this.search(fen, movetimeMs, depth, multiPv);
    const result = this.tail.then(run, run);
    this.tail = result.catch(() => {});
    return result;
  }

  async search(fen, movetimeMs, depth, multiPv) {
    await this.start();
    this.worker.postMessage("setoption name MultiPV value " + multiPv);
    await this.waitFor("readyok", "isready");
    return new Promise((resolve) => {
      const lines = new Map();
      this.listener = (line) => {
        if (line.startsWith("info ") && line.includes(" pv ") && !line.includes("bound")) {
          const tokens = line.split(" ");
          const at = tokens.indexOf("multipv");
          lines.set(at < 0 ? 1 : Number(tokens[at + 1]), line);
        } else if (line.startsWith("bestmove")) {
          this.listener = null;
          resolve([...lines.keys()].sort((a, b) => a - b).map((key) => lines.get(key)).join("|"));
        }
      };
      this.worker.postMessage("position fen " + fen);
      const limit = depth > 0 ? "depth " + depth + " movetime " + movetimeMs : "movetime " + movetimeMs;
      this.worker.postMessage("go " + limit);
    });
  }
}
