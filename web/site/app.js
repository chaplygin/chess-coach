// Страница разбора партий. Вся логика тренера живёт в coach.js (собран из :core
// и :coach), здесь только экран, вход в Lichess и общение с движком.
import { classify, explain, replay, terminalScore, tryCandidate } from "./coach.js";
import { Engine } from "./engine.js";

const LICHESS = "https://lichess.org";
const CLIENT_ID = "org.chesscoach.app";
const REDIRECT_URI = location.origin + location.pathname;
const NEWLINE = String.fromCharCode(10);
const TEXT_GLYPH = String.fromCharCode(0xfe0e);
const SCAN_MOVETIME_MS = 700;
const MATED_CP = 100000;
const FIGURINES = { K: "♚", Q: "♛", R: "♜", B: "♝", N: "♞" };
const ARROW_COLORS = { BEST_MOVE: "#2f9e62", PLAYED_MOVE: "#3b78c4", THREAT: "#d0442f", CONSEQUENCE: "#d6972a" };
const ARROW_NAMES = { BEST_MOVE: "лучший ход", PLAYED_MOVE: "сыгранный", THREAT: "угроза", CONSEQUENCE: "следствие" };
const CLASS_MARK = { INACCURACY: "?!", MISTAKE: "?", BLUNDER: "??" };

const app = document.getElementById("app");
const engine = new Engine("engine/stockfish-19-lite-single.js");

// Хранилище браузера может быть недоступно (приватный режим): страница должна работать и так.
const store = {
  get(key) { try { return localStorage.getItem(key); } catch { return null; } },
  set(key, value) { try { localStorage.setItem(key, value); } catch { /* разбор просто не сохранится */ } },
  remove(key) { try { localStorage.removeItem(key); } catch { /* нечего удалять */ } },
};

function h(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (key === "class") node.className = value;
    else if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else if (value !== undefined && value !== null) node.setAttribute(key, value);
  }
  node.append(...children.flat().filter((child) => child !== null && child !== undefined));
  return node;
}

// ---------- Вход ----------

function base64Url(bytes) {
  return btoa(String.fromCharCode(...bytes)).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

function randomToken() {
  return base64Url(crypto.getRandomValues(new Uint8Array(32)));
}

// Как и в приложении, токен запрашивается без разрешений: он не может сделать ход на аккаунте.
async function startLogin() {
  const verifier = randomToken();
  const state = randomToken();
  store.set("oauth", JSON.stringify({ verifier, state }));
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
  const query = new URLSearchParams({
    response_type: "code",
    client_id: CLIENT_ID,
    redirect_uri: REDIRECT_URI,
    code_challenge_method: "S256",
    code_challenge: base64Url(new Uint8Array(digest)),
    state,
  });
  location.assign(LICHESS + "/oauth?" + query);
}

async function completeLogin(params) {
  const pending = JSON.parse(store.get("oauth") || "null");
  store.remove("oauth");
  history.replaceState(null, "", REDIRECT_URI);
  if (params.get("error")) throw new Error("Доступ не выдан: " + (params.get("error_description") || params.get("error")));
  if (!pending || pending.state !== params.get("state")) throw new Error("Ответ не соответствует запросу, вход отклонён");

  const response = await fetch(LICHESS + "/api/token", {
    method: "POST",
    body: new URLSearchParams({
      grant_type: "authorization_code",
      code: params.get("code"),
      code_verifier: pending.verifier,
      redirect_uri: REDIRECT_URI,
      client_id: CLIENT_ID,
    }),
  });
  if (!response.ok) throw new Error("Lichess отклонил вход: HTTP " + response.status);
  store.set("token", (await response.json()).access_token);
}

async function lichessGet(path, accept = "application/json") {
  const headers = { Accept: accept };
  const token = store.get("token");
  if (token) headers.Authorization = "Bearer " + token;
  const response = await fetch(LICHESS + path, { headers }).catch(() => {
    throw new Error("Не удалось связаться с Lichess: проверьте сеть, антивирус или блокировщик");
  });
  if (response.status === 401) {
    store.remove("token");
    throw new Error("Вход устарел, войдите ещё раз");
  }
  if (response.status === 429) throw new Error("Слишком много запросов к Lichess, повторите через минуту");
  if (!response.ok) throw new Error("Lichess вернул HTTP " + response.status);
  return response.text();
}

async function signOut() {
  const token = store.get("token");
  store.remove("token");
  store.remove("username");
  if (token) {
    fetch(LICHESS + "/api/token", { method: "DELETE", headers: { Authorization: "Bearer " + token } }).catch(() => {});
  }
  showStart();
}

function renderAccount() {
  const box = document.getElementById("account");
  const username = store.get("username");
  box.replaceChildren();
  if (username) {
    box.append(h("span", {}, username));
    box.append(h("button", { onclick: signOut }, store.get("token") ? "Выйти" : "Сменить игрока"));
  }
}

// ---------- Список партий ----------

function showStart(errorText) {
  renderAccount();
  const input = h("input", { placeholder: "Ник на Lichess", "aria-label": "Ник на Lichess", autocomplete: "off" });
  const form = h("form", {
    class: "row",
    onsubmit: (event) => {
      event.preventDefault();
      const name = input.value.trim();
      if (!name) return;
      store.set("username", name);
      showGames();
    },
  }, input, h("button", { type: "submit" }, "Показать партии"));

  app.replaceChildren(h("section", { class: "intro" },
    h("h1", {}, "Тренер объяснит, почему ход был лучше"),
    h("p", {}, "Разбор завершённых партий Lichess на русском языке. Stockfish и тренер работают прямо в браузере, партии никуда не отправляются."),
    errorText ? h("p", { class: "error" }, errorText) : null,
    h("div", { class: "row" }, h("button", { class: "primary", onclick: startLogin }, "Войти через Lichess")),
    form,
    h("p", { class: "note" }, "Для просмотра публичных партий вход не нужен. Подсказок в идущих партиях здесь нет и не будет: только разбор завершённых."),
  ));
}

function toGame(raw, username) {
  // Только завершённые партии в обычные шахматы: те же границы, что и в приложении.
  if (raw.variant !== "standard" || raw.status === "started" || raw.status === "created" || !raw.moves) return null;
  const whiteName = raw.players?.white?.user?.name;
  const myColor = whiteName && whiteName.toLowerCase() === username.toLowerCase() ? "white" : "black";
  const other = raw.players?.[myColor === "white" ? "black" : "white"] || {};
  const outcome = !raw.winner ? "DRAW" : raw.winner === myColor ? "WIN" : "LOSS";
  return {
    id: raw.id,
    myColor,
    outcome,
    opponent: other.user?.name || (other.aiLevel ? "Компьютер, уровень " + other.aiLevel : "Аноним"),
    rating: other.rating,
    speed: raw.speed,
    createdAt: raw.createdAt,
    opening: raw.opening?.name || "",
    moves: raw.moves,
    initialFen: raw.initialFen || "",
  };
}

const OUTCOME_TEXT = { WIN: "Победа", LOSS: "Поражение", DRAW: "Ничья" };

async function showGames() {
  renderAccount();
  const username = store.get("username");
  app.replaceChildren(h("p", { class: "note" }, "Загружаю партии " + username + "…"));
  try {
    const body = await lichessGet(
      "/api/games/user/" + encodeURIComponent(username) + "?max=40&moves=true&opening=true&sort=dateDesc",
      "application/x-ndjson",
    );
    const games = body.split(NEWLINE).filter(Boolean).map((line) => {
      try { return toGame(JSON.parse(line), username); } catch { return null; }
    }).filter(Boolean);

    if (games.length === 0) {
      app.replaceChildren(h("p", {}, "У игрока " + username + " нет завершённых партий в обычные шахматы."));
      return;
    }
    app.replaceChildren(
      h("h2", {}, "Партии " + username),
      h("ul", { class: "games" }, games.map((game) => h("li", {},
        h("button", { onclick: () => showReview(game) },
          h("span", {}, "против " + game.opponent + (game.rating ? " (" + game.rating + ")" : "")),
          h("span", { class: "result-" + game.outcome }, OUTCOME_TEXT[game.outcome]),
          h("span", { class: "sub" }, [
            new Date(game.createdAt).toLocaleDateString("ru-RU"), game.speed, game.opening,
          ].filter(Boolean).join(" · ")),
        ),
      ))),
    );
  } catch (error) {
    showStart(error.message);
  }
}

// ---------- Разбор ----------

// Запись хода значком фигуры, как в списке ходов на Lichess.
function figurine(san) {
  return [...san].map((char) => (FIGURINES[char] ? FIGURINES[char] + TEXT_GLYPH : char)).join("");
}

// Оценка после хода: плюс за белых, минус за чёрных. Та же запись, что ScoreFormat.short в приложении.
function shortScore({ cp, mate }) {
  if (mate !== null) return "#" + mate;
  if (cp === null) return "";
  if (Math.abs(cp) >= MATED_CP) return "#";
  const body = (cp / 100).toFixed(1);
  if (body === "0.0" || body === "-0.0") return "0.0";
  return cp > 0 ? "+" + body : body;
}

function parsePositions(text) {
  return text.split(";").filter(Boolean).map((row) => {
    const f = row.split("|");
    return { fenBefore: f[1], fenAfter: f[2], san: f[3], uci: f[4], moveNumber: Number(f[5]), white: f[6] === "true" };
  });
}

function parseEvaluations(text) {
  return text.split(";").filter(Boolean).map((row) => {
    const f = row.split("|");
    return { cls: f[0], loss: Number(f[1]), cp: f[2] === "" ? null : Number(f[2]), mate: f[3] === "" ? null : Number(f[3]) };
  });
}

function scoreToken(infoLines) {
  const first = infoLines.split("|")[0] || "";
  const tokens = first.split(" ");
  const at = tokens.indexOf("score");
  if (at < 0) return "";
  return tokens[at + 1] + ":" + tokens[at + 2];
}

function bridge(guard) {
  return (fen, movetimeMs, depth, multiPv, done) => {
    if (!guard()) { done(""); return; }
    engine.analyse(fen, movetimeMs, depth, multiPv).then(done, () => done(""));
  };
}

function drawBoard(fen, flipped, lastUci, arrows) {
  const placement = fen.split(" ")[0].split("/");
  const squares = [];
  for (let rank = 0; rank < 8; rank++) {
    let file = 0;
    for (const char of placement[rank]) {
      const empties = Number(char);
      if (empties) {
        for (let i = 0; i < empties; i++) squares.push({ rank, file: file++, piece: null });
      } else {
        squares.push({ rank, file: file++, piece: char });
      }
    }
  }
  if (flipped) squares.reverse();

  const name = (s) => "abcdefgh"[s.file] + (8 - s.rank);
  const lit = lastUci ? [lastUci.slice(0, 2), lastUci.slice(2, 4)] : [];
  const pieceFile = (piece) => "pieces/" + (piece === piece.toUpperCase() ? "w" : "b") + piece.toUpperCase() + ".svg";
  // Как на Lichess: буквы файлов внизу слева, номера горизонталей справа сверху.
  const board = h("div", { class: "board", role: "img", "aria-label": "Позиция на доске" }, squares.map((s, index) => h("div", {
    class: "sq " + ((s.rank + s.file) % 2 ? "d" : "l") + (lit.includes(name(s)) ? " hl" : ""),
  },
    index >= 56 ? h("span", { class: "coord file" }, name(s)[0]) : null,
    index % 8 === 7 ? h("span", { class: "coord rank" }, name(s)[1]) : null,
    s.piece ? h("img", { class: "pc", src: pieceFile(s.piece), alt: "", draggable: "false" }) : null,
  )));

  const wrap = h("div", { class: "board-wrap" }, board);
  if (arrows.length) wrap.append(drawArrows(arrows, flipped));
  return wrap;
}

function drawArrows(arrows, flipped) {
  const NS = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(NS, "svg");
  svg.setAttribute("viewBox", "0 0 80 80");
  svg.setAttribute("class", "arrows");
  const center = (square) => {
    const file = "abcdefgh".indexOf(square[0]);
    const rank = Number(square[1]) - 1;
    return flipped ? [(7 - file) * 10 + 5, rank * 10 + 5] : [file * 10 + 5, (7 - rank) * 10 + 5];
  };
  for (const { uci, style } of arrows) {
    const [x1, y1] = center(uci.slice(0, 2));
    const [x2, y2] = center(uci.slice(2, 4));
    const length = Math.hypot(x2 - x1, y2 - y1) || 1;
    const ux = (x2 - x1) / length;
    const uy = (y2 - y1) / length;
    const color = ARROW_COLORS[style] || ARROW_COLORS.BEST_MOVE;
    const line = document.createElementNS(NS, "line");
    const tipX = x2 - ux * 3.2;
    const tipY = y2 - uy * 3.2;
    Object.entries({ x1, y1, x2: tipX, y2: tipY, stroke: color, "stroke-width": 1.8, "stroke-linecap": "round", opacity: 0.85 })
      .forEach(([key, value]) => line.setAttribute(key, value));
    const head = document.createElementNS(NS, "polygon");
    head.setAttribute("points", [
      [x2, y2],
      [tipX - uy * 2.2, tipY + ux * 2.2],
      [tipX + uy * 2.2, tipY - ux * 2.2],
    ].map((p) => p.join(",")).join(" "));
    head.setAttribute("fill", color);
    head.setAttribute("opacity", 0.85);
    svg.append(line, head);
  }
  return svg;
}

function drawGraph(evaluations, ply, onPick) {
  const NS = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(NS, "svg");
  svg.setAttribute("class", "graph");
  svg.setAttribute("viewBox", "0 0 100 40");
  svg.setAttribute("preserveAspectRatio", "none");
  svg.setAttribute("role", "img");
  svg.setAttribute("aria-label", "График оценки партии");
  const y = (e) => {
    const cp = e.mate !== null ? (e.mate > 0 ? 500 : -500) : Math.max(-500, Math.min(500, e.cp));
    return 20 - cp / 25;
  };
  const stepX = 100 / Math.max(1, evaluations.length - 1);
  const axis = document.createElementNS(NS, "line");
  Object.entries({ x1: 0, y1: 20, x2: 100, y2: 20, stroke: "currentColor", opacity: 0.25, "stroke-width": 0.4, "vector-effect": "non-scaling-stroke" })
    .forEach(([key, value]) => axis.setAttribute(key, value));
  const path = document.createElementNS(NS, "polyline");
  path.setAttribute("points", evaluations.map((e, i) => i * stepX + "," + y(e)).join(" "));
  Object.entries({ fill: "none", stroke: "var(--accent)", "stroke-width": 1.5, "vector-effect": "non-scaling-stroke" })
    .forEach(([key, value]) => path.setAttribute(key, value));
  svg.append(axis, path);
  if (ply > 0) {
    const mark = document.createElementNS(NS, "line");
    const x = (ply - 1) * stepX;
    Object.entries({ x1: x, y1: 0, x2: x, y2: 40, stroke: "var(--mistake)", "stroke-width": 1, "vector-effect": "non-scaling-stroke" })
      .forEach(([key, value]) => mark.setAttribute(key, value));
    svg.append(mark);
  }
  svg.addEventListener("click", (event) => {
    const rect = svg.getBoundingClientRect();
    onPick(Math.round(((event.clientX - rect.left) / rect.width) * (evaluations.length - 1)) + 1);
  });
  return svg;
}

function showReview(game) {
  const positions = parsePositions(replay(game.moves, game.initialFen));
  const flipped = game.myColor === "black";
  const cacheKey = "analysis:" + game.id;
  const startFen = positions.length ? positions[0].fenBefore : game.initialFen;

  let ply = 0;
  let evaluations = parseEvaluations(store.get(cacheKey) || "");
  if (evaluations.length !== positions.length) evaluations = [];
  let scan = null;
  let coachText = "";
  let candidateText = "";
  let coachWaiting = false;
  let arrows = [];
  let request = 0;
  let closed = false;
  let timer = null;

  const fenAt = (index) => (index === 0 ? startFen : positions[index - 1].fenAfter);

  function goTo(next) {
    ply = Math.max(0, Math.min(positions.length, next));
    arrows = [];
    render();
    clearTimeout(timer);
    // Пауза, чтобы быстрое перелистывание не ставило движку очередь из позиций.
    timer = setTimeout(askCoach, 250);
  }

  function askCoach() {
    const token = ++request;
    coachWaiting = true;
    coachText = "Тренер думает…";
    render();
    explain(fenAt(ply), positions[ply]?.uci || "", game.opening, bridge(() => token === request && !closed), (text, marks, error) => {
      if (token !== request || closed) return;
      coachWaiting = false;
      coachText = error ? "Не удалось разобрать позицию: " + error : text;
      arrows = (marks || "").split(";").filter(Boolean).map((item) => {
        const [uci, style] = item.split(":");
        return { uci, style };
      });
      render();
    });
  }

  function submitCandidate(input) {
    const typed = input.trim();
    if (!typed) return;
    const token = ++request;
    coachWaiting = true;
    coachText = "Считаю ход " + typed + "…";
    render();
    tryCandidate(fenAt(ply), typed, bridge(() => token === request && !closed), (text, _marks, error) => {
      if (token !== request || closed) return;
      coachWaiting = false;
      coachText = error ? "Не удалось разобрать ход: " + error : text;
      render();
    });
  }

  // Прогресс обновляется точечно: полная перерисовка раз в секунду сбивала бы
  // прокрутку и ввод своего хода, пока идёт разбор.
  function showScanProgress() {
    const bar = app.querySelector(".scan-bar");
    const label = app.querySelector(".scan-label");
    if (bar) bar.style.width = Math.round((scan.done / scan.total) * 100) + "%";
    if (label) label.textContent = "Движок считает партию: " + scan.done + " из " + scan.total + " позиций";
  }

  async function runScan() {
    scan = { done: 0, total: positions.length + 1, error: null };
    render();
    const fens = [startFen, ...positions.map((p) => p.fenAfter)];
    const scores = [];
    try {
      for (const fen of fens) {
        if (closed) return;
        const info = await engine.analyse(fen, SCAN_MOVETIME_MS, 0, 1);
        const score = scoreToken(info) || terminalScore(fen);
        if (!score) throw new Error("движок не вернул оценку");
        scores.push(score);
        scan.done = scores.length;
        showScanProgress();
      }
      const positionsText = replay(game.moves, game.initialFen);
      const result = classify(positionsText, scores.join(";"));
      evaluations = parseEvaluations(result);
      store.set(cacheKey, result);
      scan = null;
    } catch (error) {
      scan = { done: 0, total: 1, error: error.message };
    }
    render();
  }

  function render() {
    const moveList = h("div", { class: "moves" });
    positions.forEach((position, index) => {
      if (position.white) moveList.append(h("span", { class: "num" }, position.moveNumber + "."));
      else if (index === 0) moveList.append(h("span", { class: "num" }, position.moveNumber + "."), h("span", {}));
      const evaluation = evaluations[index];
      const cls = evaluation ? evaluation.cls : "";
      moveList.append(h("button", {
        class: "cls-" + cls + (index + 1 === ply ? " on" : ""),
        onclick: () => goTo(index + 1),
      },
        h("span", {}, figurine(position.san) + (CLASS_MARK[cls] || "")),
        evaluation ? h("span", { class: "eval" }, shortScore(evaluation)) : null,
      ));
    });

    const candidateHadFocus = document.activeElement?.classList.contains("candidate");
    const candidate = h("input", {
      class: "candidate",
      placeholder: "Свой ход, например Nf3",
      "aria-label": "Свой ход",
      value: candidateText,
      oninput: (event) => { candidateText = event.target.value; },
    });
    const usedStyles = [...new Set(arrows.map((a) => a.style))];
    const left = h("div", {},
      drawBoard(fenAt(ply), flipped, ply > 0 ? positions[ply - 1].uci : "", arrows),
      usedStyles.length ? h("div", { class: "legend" }, usedStyles.map((style) => h("span", {},
        h("i", { style: "background:" + ARROW_COLORS[style] }), ARROW_NAMES[style] || style))) : null,
      h("div", { class: "controls" },
        h("button", { onclick: () => goTo(0), "aria-label": "К началу" }, "⏮"),
        h("button", { onclick: () => goTo(ply - 1), "aria-label": "Назад" }, "◀"),
        h("button", { onclick: () => goTo(ply + 1), "aria-label": "Вперёд" }, "▶"),
        h("button", { onclick: () => goTo(positions.length), "aria-label": "В конец" }, "⏭"),
      ),
    );

    const right = h("div", {},
      h("button", { onclick: () => { closed = true; clearTimeout(timer); document.removeEventListener("keydown", onKey); showGames(); } }, "← К партиям"),
      h("h2", { style: "margin-top:12px" }, "против " + game.opponent),
      h("p", { class: "note", style: "margin:0" }, [OUTCOME_TEXT[game.outcome], game.opening].filter(Boolean).join(" · ")),
      evaluations.length ? drawGraph(evaluations, ply, goTo) : null,
      scan && !scan.error ? h("div", {},
        h("div", { class: "progress" }, h("div", { class: "scan-bar", style: "width:" + Math.round((scan.done / scan.total) * 100) + "%" })),
        h("p", { class: "note scan-label" }, "Движок считает партию: " + scan.done + " из " + scan.total + " позиций"),
      ) : null,
      scan && scan.error ? h("p", { class: "error" }, "Разбор не удался: " + scan.error) : null,
      scan && scan.error ? h("div", { class: "row" },
        h("button", { class: "primary", onclick: runScan }, "Разобрать ещё раз"),
      ) : null,
      moveList,
      h("div", { class: "coach" + (coachWaiting ? " wait" : "") }, coachText),
      h("form", {
        class: "row",
        onsubmit: (event) => { event.preventDefault(); submitCandidate(candidate.value); },
      }, candidate, h("button", { type: "submit" }, "А если так?")),
    );

    app.replaceChildren(h("div", { class: "review" }, left, right));
    if (candidateHadFocus) candidate.focus();
    moveList.querySelector(".on")?.scrollIntoView({ block: "nearest" });
  }

  function onKey(event) {
    if (event.target.tagName === "INPUT") return;
    if (event.key === "ArrowLeft") goTo(ply - 1);
    if (event.key === "ArrowRight") goTo(ply + 1);
  }
  document.addEventListener("keydown", onKey);

  render();
  window.scrollTo(0, 0);
  goTo(0);
  // Разбор всей партии стартует сам: сразу видно, какие ходы ключевые.
  if (!evaluations.length) runScan();
}

// ---------- Запуск ----------

async function start() {
  const params = new URLSearchParams(location.search);
  if (params.get("code") || params.get("error")) {
    try {
      await completeLogin(params);
      const account = JSON.parse(await lichessGet("/api/account"));
      store.set("username", account.username);
    } catch (error) {
      showStart(error.message);
      return;
    }
  }
  if (store.get("username")) showGames();
  else showStart();
}

start();
