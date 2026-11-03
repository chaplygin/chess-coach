// Страница разбора партий. Вся логика тренера живёт в coach.js (собран из :core
// и :coach), здесь только экран, вход в Lichess и общение с движком.
import { classify, errorReport, explain, gameEnd, legalMoves, recap, replay, sanLine, terminalScore } from "./coach.js";
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
    h("h1", { class: "large-title" }, "Наставник"),
    h("p", {}, "Тренер объяснит, почему ход был лучше. Разбор завершённых партий Lichess на русском языке: Stockfish и тренер работают прямо в браузере, партии никуда не отправляются."),
    errorText ? h("p", { class: "error" }, errorText) : null,
    h("div", { class: "card" },
      h("div", { class: "row" }, h("button", { class: "primary", onclick: startLogin }, "Войти через Lichess")),
      form,
    ),
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
    status: raw.status || "",
    opponent: other.user?.name || (other.aiLevel ? "Компьютер, уровень " + other.aiLevel : "Аноним"),
    rating: other.rating,
    speed: raw.speed,
    createdAt: raw.createdAt,
    opening: raw.opening?.name || "",
    moves: raw.moves,
    initialFen: raw.initialFen || "",
    clocks: raw.clocks || [],
    initialClock: raw.clock?.initial ?? "",
  };
}

const OUTCOME_TEXT = { WIN: "Победа", LOSS: "Поражение", DRAW: "Ничья" };
const REFERENCE_TITLE = "ТЕРМИНЫ";

// Сводка считается только по партиям, которые уже разобраны на этом устройстве.
function reportPayload(games) {
  return games.map((game) => {
    const evaluations = parseEvaluations(store.get("analysis:" + game.id) || "");
    if (!evaluations.length) return null;
    return [game.myColor, game.moves, game.initialFen, evaluations.map((e) => e.cls).join(",")].join("|");
  }).filter(Boolean).join("~");
}

function showReport(games) {
  const box = h("div", { class: "coach" }, "Считаю…");
  app.replaceChildren(
    h("button", { class: "link", onclick: showGames }, "‹ Партии"),
    h("h1", { class: "large-title" }, "Мои ошибки"),
    box,
  );
  errorReport(reportPayload(games), (text, _marks, error) => {
    box.textContent = error ? "Не удалось собрать сводку: " + error : text;
  });
}

function menuRow(icon, tint, title, subtitle, onclick) {
  return h("button", { class: "menu-row", onclick },
    h("span", { class: "menu-icon", style: "background:" + tint }, icon),
    h("span", { class: "menu-text" }, h("span", {}, title), h("span", { class: "sub" }, subtitle)),
    h("span", { class: "chevron" }, "›"),
  );
}

async function showGames() {
  renderAccount();
  const username = store.get("username");
  app.replaceChildren(h("p", { class: "note" }, "Загружаю партии " + username + "…"));
  try {
    const body = await lichessGet(
      "/api/games/user/" + encodeURIComponent(username) + "?max=40&moves=true&opening=true&clocks=true&sort=dateDesc",
      "application/x-ndjson",
    );
    const games = body.split(NEWLINE).filter(Boolean).map((line) => {
      try { return toGame(JSON.parse(line), username); } catch { return null; }
    }).filter(Boolean);

    if (games.length === 0) {
      app.replaceChildren(h("p", {}, "У игрока " + username + " нет завершённых партий в обычные шахматы."));
      return;
    }
    const last = games[0];
    // Главную ошибку последней партии показываем, только если она уже разобрана: ради карточки движок не гоняем.
    const lastRecap = recapOf(last, parseEvaluations(store.get("analysis:" + last.id) || ""));
    const lastMoment = lastRecap?.moments.slice().sort((a, b) => b.swing - a.swing)[0];
    app.replaceChildren(
      h("h1", { class: "large-title" }, "Наставник"),
      h("div", { class: "card profile" },
        h("span", { class: "avatar" }, username.slice(0, 1).toUpperCase()),
        h("span", { class: "menu-text" }, h("strong", {}, username), h("span", { class: "sub" }, "Аккаунт Lichess")),
      ),
      h("h3", { class: "section" }, "Последняя партия"),
      h("button", { class: "card last-game", onclick: () => showRecap(last) },
        h("span", { class: "spread" }, h("strong", {}, "против " + last.opponent), h("span", { class: "result-" + last.outcome }, OUTCOME_TEXT[last.outcome])),
        h("span", { class: "sub" }, lastRecap
          ? (lastMoment ? lastMoment.title + " · " + lastMoment.detail : "Крупных ошибок не нашлось")
          : "Ещё не разобрана: откройте, разбор запустится сам"),
        h("span", { class: "accent" }, "Открыть итоги"),
      ),
      h("h3", { class: "section" }, "Сводка"),
      h("div", { class: "group" }, menuRow("◎", "#ff3b30", "Мои ошибки", "Что повторяется из партии в партию", () => showReport(games))),
      h("h3", { class: "section" }, "Партии " + username),
      h("div", { class: "group" }, games.map((game) => h("button", { class: "menu-row game", onclick: () => showRecap(game) },
        h("span", { class: "menu-text" },
          h("span", {}, "против " + game.opponent + (game.rating ? " (" + game.rating + ")" : "")),
          h("span", { class: "sub" }, [
            new Date(game.createdAt).toLocaleDateString("ru-RU"), game.speed, game.opening,
          ].filter(Boolean).join(" · ")),
        ),
        h("span", { class: "result-" + game.outcome }, OUTCOME_TEXT[game.outcome]),
        h("span", { class: "chevron" }, "›"),
      ))),
    );
  } catch (error) {
    showStart(error.message);
  }
}

// ---------- Общие куски разбора ----------

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

const CLASS_LABEL = { BEST: "лучший ход", GOOD: "хороший ход", INACCURACY: "неточность", MISTAKE: "ошибка", BLUNDER: "зевок" };

function moveLabel(position) {
  return position.moveNumber + (position.white ? ". " : "... ") + position.san;
}

// Заголовок хода, как MoveHeadline в приложении: "8. Qh5+ — зевок: +0.5 → -2.9".
function headline(position, before, after) {
  let text = moveLabel(position);
  if (after) text += " — " + CLASS_LABEL[after.cls];
  if (before && after) text += ": " + shortScore(before) + " → " + shortScore(after);
  else if (after) text += ": " + shortScore(after);
  return text;
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
    return { cls: f[0], loss: Number(f[1]), cp: f[2] === "" ? null : Number(f[2]), mate: f[3] === "" ? null : Number(f[3]), missed: f[4] === "true" };
  });
}

function scoreToken(infoLines) {
  const first = infoLines.split("|")[0] || "";
  const tokens = first.split(" ");
  const at = tokens.indexOf("score");
  if (at < 0) return "";
  return tokens[at + 1] + ":" + tokens[at + 2];
}

function parseVariations(text) {
  return (text || "").split(";").filter(Boolean).map((row) => {
    const f = row.split("|");
    return { title: f[0], fens: f[1].split(","), san: f[2].split(" ") };
  });
}

const MOMENT_COLORS = {
  MY_ERROR: "#ff3b30", TIME_TROUBLE: "#ff3b30", LOST_ADVANTAGE: "#ff3b30",
  MISSED_CHANCE: "#ff9500", OPPONENT_ERROR: "#34c759", MY_STRIKE: "#34c759", OPPONENT_STRIKE: "#af52de",
};

// Итоги партии считает тренер; по неполному скану тоже — так первый перелом виден раньше.
function recapOf(game, evaluations) {
  if (!evaluations.length) return null;
  const rows = evaluations.map((e) => [e.cls, e.loss, e.cp ?? "", e.mate ?? "", e.missed ? "true" : "false"].join("|")).join(";");
  const text = recap([game.myColor, game.moves, game.initialFen, rows, game.outcome, game.status || "", (game.clocks || []).join(","), game.initialClock ?? ""].join("~"));
  const [verdict, me, opponent, momentRows] = text.split("~");
  const stats = (s) => { const [accuracy, blunders, mistakes, inaccuracies] = s.split(",").map(Number); return { accuracy, blunders, mistakes, inaccuracies }; };
  const moments = (momentRows || "").split(";").filter(Boolean).map((row) => {
    const [ply, kind, title, detail, swing] = row.split("|");
    return { ply: Number(ply), kind, title, detail, swing: Number(swing) };
  });
  return { verdict, me: stats(me), opponent: stats(opponent), moments };
}

// ---------- Скан партии движком ----------

// Один скан на партию, на него подписываются и итоги, и разбор по ходам.
const scans = new Map();

function scanFor(game) {
  const cached = parseEvaluations(store.get("analysis:" + game.id) || "");
  const positionsText = replay(game.moves, game.initialFen);
  const total = parsePositions(positionsText).length;
  if (cached.length === total) return { done: total, total, evaluations: cached, final: true, error: null, listeners: new Set() };
  let scan = scans.get(game.id);
  if (scan && !scan.error) return scan;
  scan = { done: 0, total, evaluations: [], final: false, error: null, listeners: new Set() };
  scans.set(game.id, scan);
  runScan(game, scan, positionsText);
  return scan;
}

function notify(scan) {
  scan.listeners.forEach((listener) => listener());
}

async function runScan(game, scan, positionsText) {
  const positions = parsePositions(positionsText);
  const rows = positionsText.split(";").filter(Boolean);
  const fens = [positions.length ? positions[0].fenBefore : game.initialFen, ...positions.map((p) => p.fenAfter)];
  const scores = [];
  try {
    for (const fen of fens) {
      const info = await engine.analyse(fen, SCAN_MOVETIME_MS, 0, 1);
      const score = scoreToken(info) || terminalScore(fen);
      if (!score) throw new Error("движок не вернул оценку");
      scores.push(score);
      scan.done = Math.min(scores.length, positions.length);
      // Ход оценён, как только посчитана позиция после него: график растёт по ходу скана.
      if (scores.length >= 2) scan.evaluations = parseEvaluations(classify(rows.slice(0, scores.length - 1).join(";"), scores.join(";")));
      notify(scan);
    }
    const result = classify(positionsText, scores.join(";"));
    scan.evaluations = parseEvaluations(result);
    scan.final = true;
    store.set("analysis:" + game.id, result);
  } catch (error) {
    scan.error = error.message;
  }
  notify(scan);
}

// Полоска прогресса или ошибка скана.
function scanBlock(scan, onRetry) {
  if (scan.final) return null;
  if (scan.error) {
    return h("div", { class: "card" }, h("p", { class: "error" }, "Разбор не удался: " + scan.error),
      h("button", { class: "primary", onclick: onRetry }, "Разобрать ещё раз"));
  }
  return h("div", {},
    h("div", { class: "progress" }, h("div", { class: "scan-bar", style: "width:" + Math.round((scan.done / Math.max(1, scan.total)) * 100) + "%" })),
    h("p", { class: "note scan-label" }, "Движок разбирает партию: " + scan.done + " из " + scan.total),
  );
}

// ---------- Итоги партии ----------

function showRecap(game) {
  let closed = false;
  const scan = scanFor(game);
  const listener = () => { if (!closed) render(); };
  scan.listeners.add(listener);
  const leave = (next) => { closed = true; scan.listeners.delete(listener); next(); };

  function render() {
    const result = recapOf(game, scan.evaluations);
    const total = parsePositions(replay(game.moves, game.initialFen)).length;
    const first = result?.moments[0];
    app.replaceChildren(h("section", { class: "recap" },
      h("div", { class: "spread" },
        h("button", { class: "link", onclick: () => leave(showGames) }, "‹ Партии"),
        h("button", { class: "link", onclick: () => leave(() => showReview(game, 0)) }, "Ходы"),
      ),
      h("h1", { class: "large-title" }, "Итоги партии"),
      h("p", { class: "note" }, (game.myColor === "white" ? "Вы белыми" : "Вы чёрными") + " против " + game.opponent + " · " + OUTCOME_TEXT[game.outcome].toLowerCase()),
      scanBlock(scan, () => { scans.delete(game.id); leave(() => showRecap(game)); }),
      !scan.final && first ? h("button", { class: "link", onclick: () => leave(() => showReview(game, first.ply + 1)) }, "Первый перелом: " + first.title.split(" — ")[0] + " ›") : null,
      result ? h("div", { class: "card verdict" }, result.verdict) : null,
      result ? h("div", { class: "stats" }, statCard("Вы", result.me), statCard("Соперник", result.opponent)) : null,
      scan.evaluations.length ? h("div", { class: "card" }, drawGraph(scan.evaluations, total, 0, result?.moments || [], (p) => leave(() => showReview(game, p)))) : null,
      result && result.moments.length ? h("h3", { class: "section" }, "Ключевые моменты") : null,
      result && result.moments.length ? h("div", { class: "group" }, result.moments.map((m) => h("button", { class: "menu-row", onclick: () => leave(() => showReview(game, m.ply + 1)) },
        h("span", { class: "dot", style: "background:" + MOMENT_COLORS[m.kind] }),
        h("span", { class: "menu-text" }, h("span", {}, m.title), h("span", { class: "sub" }, m.detail)),
        h("span", { class: "chevron" }, "›"),
      ))) : null,
      h("button", { class: "primary wide", onclick: () => leave(() => showReview(game, (first ? first.ply : 0) + 1)) }, "Разобрать по ходам"),
    ));
  }

  render();
  window.scrollTo(0, 0);
}

function statCard(title, stats) {
  return h("div", { class: "card stat" },
    h("span", { class: "sub" }, title),
    h("strong", {}, stats.accuracy + "%"),
    h("span", { class: "sub" }, "зевки " + stats.blunders + " · ошибки " + stats.mistakes + " · неточн. " + stats.inaccuracies),
  );
}

// ---------- Доска ----------

function drawBoard(fen, flipped, lastUci, arrows, interaction) {
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
  const selected = interaction?.selected;
  const targets = interaction?.targets || [];
  const pieceFile = (piece) => "pieces/" + (piece === piece.toUpperCase() ? "w" : "b") + piece.toUpperCase() + ".svg";
  // Как на Lichess: буквы файлов внизу слева, номера горизонталей справа сверху.
  const board = h("div", { class: "board" + (interaction ? " live" : ""), role: "img", "aria-label": "Позиция на доске" }, squares.map((s, index) => h("div", {
    class: "sq " + ((s.rank + s.file) % 2 ? "d" : "l") + (name(s) === selected ? " sel" : lit.includes(name(s)) ? " hl" : ""),
    onclick: interaction ? () => interaction.onSquare(name(s)) : undefined,
  },
    index >= 56 ? h("span", { class: "coord file" }, name(s)[0]) : null,
    index % 8 === 7 ? h("span", { class: "coord rank" }, name(s)[1]) : null,
    s.piece ? h("img", { class: "pc", src: pieceFile(s.piece), alt: "", draggable: "false" }) : null,
    targets.includes(name(s)) ? h("span", { class: s.piece ? "ring" : "dot-target" }) : null,
  )));

  const wrap = h("div", { class: "board-wrap" }, board);
  if (arrows.length) wrap.append(drawArrows(arrows, flipped));
  return wrap;
}

// Полоска оценки как на Lichess: белая часть со стороны белых, число на стороне ведущего.
function drawEvalBar(evaluation, flipped) {
  const known = evaluation && (evaluation.mate !== null || evaluation.cp !== null);
  const share = !known ? 0.5
    : evaluation.mate !== null ? (evaluation.mate >= 0 ? 1 : 0)
    : 1 / (1 + Math.exp(-0.00368208 * evaluation.cp));
  const whiteLeads = share >= 0.5;
  const labelOnTop = whiteLeads === flipped;
  return h("div", { class: "evalbar", role: "img", "aria-label": "Полоска оценки" },
    h("div", {
      class: "evalbar-white",
      style: "height:" + share * 100 + "%;" + (flipped ? "top:0" : "bottom:0"),
    }),
    known ? h("span", {
      class: "evalbar-label " + (whiteLeads ? "on-white" : "on-black"),
      style: labelOnTop ? "top:2px" : "bottom:2px",
    }, shortScore(evaluation)) : null,
  );
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

// График оценки: ширина — вся партия, поэтому во время скана он растёт слева направо.
// Вертикальная линия — выбранный ход, цветные точки — ключевые моменты. [ply] — число сделанных ходов.
function drawGraph(evaluations, total, ply, moments, onPick) {
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
  const stepX = 100 / Math.max(1, total - 1);
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
    Object.entries({ x1: x, y1: 0, x2: x, y2: 40, stroke: "var(--accent)", "stroke-width": 2, "vector-effect": "non-scaling-stroke" })
      .forEach(([key, value]) => mark.setAttribute(key, value));
    svg.append(mark);
  }
  for (const moment of moments) {
    const e = evaluations[moment.ply];
    if (!e) continue;
    const dot = document.createElementNS(NS, "ellipse");
    Object.entries({ cx: moment.ply * stepX, cy: Math.max(2, Math.min(38, y(e))), rx: 1.1, ry: 2.6, fill: MOMENT_COLORS[moment.kind] || "#888" })
      .forEach(([key, value]) => dot.setAttribute(key, value));
    svg.append(dot);
  }
  svg.addEventListener("click", (event) => {
    const rect = svg.getBoundingClientRect();
    onPick(Math.round(((event.clientX - rect.left) / rect.width) * (total - 1)) + 1);
  });
  return svg;
}

// Текст тренера: справочный блок свёрнут, чтобы не заслонять разбор.
function coachBlock(text, waiting) {
  const marker = NEWLINE + REFERENCE_TITLE + NEWLINE;
  const at = text.indexOf(marker);
  if (at < 0) return h("div", { class: "coach" + (waiting ? " wait" : "") }, text);
  return h("div", { class: "coach" + (waiting ? " wait" : "") },
    text.slice(0, at).trimEnd(),
    h("details", {}, h("summary", {}, "Термины"), text.slice(at + marker.length)),
  );
}

function bridge(guard) {
  return (fen, movetimeMs, depth, multiPv, done) => {
    if (!guard()) { done(""); return; }
    engine.analyse(fen, movetimeMs, depth, multiPv).then(done, () => done(""));
  };
}

function parseLegal(fen) {
  return legalMoves(fen).split(";").filter(Boolean).map((row) => {
    const [uci, san, fenAfter] = row.split("|");
    return { uci, san, fenAfter, from: uci.slice(0, 2), to: uci.slice(2, 4), promotion: uci[4] || "" };
  });
}

// Строки info движка в оценку от лица белых и вариант SAN.
function parseEngineLines(fen, infoLines) {
  const blackToMove = fen.split(" ")[1] === "b";
  return infoLines.split("|").filter(Boolean).map((line) => {
    const tokens = line.split(" ");
    const at = tokens.indexOf("score");
    const pvAt = tokens.indexOf("pv");
    if (at < 0 || pvAt < 0) return null;
    const value = Number(tokens[at + 2]) * (blackToMove ? -1 : 1);
    const pv = tokens.slice(pvAt + 1, pvAt + 7);
    return {
      score: tokens[at + 1] === "mate" ? { cp: null, mate: value } : { cp: value, mate: null },
      san: sanLine(fen, pv.join(" ")).split(" "),
      first: pv[0],
    };
  }).filter(Boolean).slice(0, 2);
}

const PROMOTION_NAMES = { q: "Ферзь", r: "Ладья", b: "Слон", n: "Конь" };

// ---------- Разбор по ходам ----------

// [startPly] — сколько ходов сделано: 0 — начальная позиция, 1 — после первого хода.
function showReview(game, startPly = 0) {
  const positionsText = replay(game.moves, game.initialFen);
  const positions = parsePositions(positionsText);
  const flipped = game.myColor === "black";
  const startFen = positions.length ? positions[0].fenBefore : game.initialFen;
  const scan = scanFor(game);

  let ply = 0;
  let recapResult = recapOf(game, scan.evaluations);
  let coachText = "";
  let coachWaiting = false;
  let arrows = [];
  let variations = [];
  // Показанный ход варианта тренера: { line, step }. Пока он задан, доска показывает вариант, а не партию.
  let shown = null;
  // Свой вариант: ходы, сыгранные на доске. Курсор — показанный ход, -1 — исходная позиция партии.
  let line = [];
  let lineCursor = -1;
  let lineText = "";
  let selected = null;
  let promotion = [];
  let engineLines = [];
  let request = 0;
  let engineRequest = 0;
  let closed = false;
  let timer = null;

  const fenAt = (index) => (index === 0 ? startFen : positions[index - 1].fenAfter);
  const lineActive = () => line.length > 0;
  const displayedFen = () => shown ? variations[shown.line].fens[shown.step]
    : lineActive() && lineCursor >= 0 ? line[lineCursor].fenAfter : fenAt(ply);

  const listener = () => {
    if (closed) return;
    recapResult = recapOf(game, scan.evaluations);
    render();
  };
  scan.listeners.add(listener);

  function leave(next) {
    closed = true;
    clearTimeout(timer);
    scan.listeners.delete(listener);
    document.removeEventListener("keydown", onKey);
    next();
  }

  function goTo(next) {
    ply = Math.max(0, Math.min(positions.length, next));
    arrows = [];
    variations = [];
    shown = null;
    line = [];
    lineCursor = -1;
    lineText = "";
    selected = null;
    promotion = [];
    coachText = ply === 0 ? "" : "Тренер думает…";
    render();
    clearTimeout(timer);
    // Пауза, чтобы быстрое перелистывание не ставило движку очередь из позиций.
    timer = setTimeout(() => { askCoach(); askEngine(); }, 250);
  }

  // Карточка разбирает сам выбранный ход: из позиции до него, со сравнением с лучшим.
  function askCoach() {
    const token = ++request;
    if (ply === 0) { coachWaiting = false; return; }
    const move = positions[ply - 1];
    coachWaiting = true;
    render();
    explain(move.fenBefore, move.uci, game.opening, game.myColor, "false", bridge(() => token === request && !closed), (text, marks, error) => {
      if (token !== request || closed) return;
      coachWaiting = false;
      coachText = error ? "Тренер не смог разобрать ход: " + error : text;
      const [arrowText, variationText] = (marks || "").split("~");
      variations = parseVariations(variationText);
      arrows = (arrowText || "").split(";").filter(Boolean).map((item) => {
        const [uci, style] = item.split(":");
        return { uci, style };
      });
      render();
    });
  }

  // Две строки движка над доской — для той позиции, что сейчас на доске.
  function askEngine() {
    const token = ++engineRequest;
    const fen = displayedFen();
    engineLines = [];
    if (gameEnd(fen)) { render(); return; }
    engine.analyse(fen, 600, 0, 2).then((info) => {
      if (token !== engineRequest || closed) return;
      engineLines = parseEngineLines(fen, info);
      // Оценка хода варианта — это оценка позиции после него.
      if (lineActive() && lineCursor >= 0 && line[lineCursor].fenAfter === fen && engineLines[0]) line[lineCursor].score = engineLines[0].score;
      render();
    }, () => {});
  }

  function playMove(move) {
    selected = null;
    promotion = [];
    if (!lineActive() && !shown && positions[ply] && positions[ply].uci === move.uci) {
      // Сыгран ход партии: просто идём по партии дальше.
      goTo(ply + 1);
      return;
    }
    const before = displayedFen();
    line = (lineActive() ? line.slice(0, lineCursor + 1) : []).concat([{ uci: move.uci, san: move.san, fenBefore: before, fenAfter: move.fenAfter, score: null }]);
    lineCursor = line.length - 1;
    lineText = "";
    render();
    askEngine();
  }

  function onSquare(square) {
    const legal = parseLegal(displayedFen());
    if (selected) {
      const candidates = legal.filter((m) => m.from === selected && m.to === square);
      if (candidates.length === 1) { playMove(candidates[0]); return; }
      if (candidates.length > 1) { promotion = candidates; render(); return; }
    }
    selected = square !== selected && legal.some((m) => m.from === square) ? square : null;
    render();
  }

  function explainLineMove() {
    const move = line[lineCursor];
    if (!move) return;
    const token = ++request;
    lineText = "Тренер разбирает ход " + move.san + "…";
    render();
    explain(move.fenBefore, move.uci, game.opening, game.myColor, "true", bridge(() => token === request && !closed), (text, _marks, error) => {
      if (token !== request || closed) return;
      lineText = error ? "Не удалось разобрать ход: " + error : text;
      render();
    });
  }

  function render() {
    const evaluations = scan.evaluations;
    const moments = recapResult?.moments || [];
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

    const fen = displayedFen();
    const legal = shown ? [] : parseLegal(fen);
    const ended = gameEnd(fen);
    const lastUci = shown ? "" : lineActive() ? (lineCursor >= 0 ? line[lineCursor].uci : "") : (ply > 0 ? positions[ply - 1].uci : "");
    const boardArrows = shown ? [] : lineActive()
      ? (engineLines[0] ? [{ uci: engineLines[0].first, style: "BEST_MOVE" }] : [])
      : arrows;
    const barScore = lineActive() ? engineLines[0]?.score : (ply > 0 ? evaluations[ply - 1] : null) || engineLines[0]?.score;
    const usedStyles = lineActive() ? [] : [...new Set(arrows.map((a) => a.style))];
    const previousMoment = [...moments].reverse().find((m) => m.ply + 1 < ply);
    const nextMoment = moments.find((m) => m.ply + 1 > ply);
    const moment = moments.find((m) => m.ply + 1 === ply);

    const left = h("div", {},
      h("div", { class: "engine-lines" }, ended ? h("strong", {}, ended) : engineLines.map((l) => h("div", { class: "engine-line" },
        h("span", { class: "score" }, shortScore(l.score)),
        h("span", {}, l.san.map(figurine).join(" ")),
      ))),
      h("div", { class: "board-row" },
        drawBoard(fen, flipped, lastUci, boardArrows, shown ? null : {
          selected,
          targets: legal.filter((m) => m.from === selected).map((m) => m.to),
          onSquare,
        }),
        drawEvalBar(barScore, flipped),
      ),
      promotion.length ? h("div", { class: "row" }, h("span", { class: "note" }, "Превратить в:"),
        promotion.slice().sort((a, b) => "qrbn".indexOf(a.promotion) - "qrbn".indexOf(b.promotion))
          .map((m) => h("button", { onclick: () => playMove(m) }, PROMOTION_NAMES[m.promotion] || m.promotion))) : null,
      usedStyles.length ? h("div", { class: "legend" }, usedStyles.map((style) => h("span", {},
        h("i", { style: "background:" + ARROW_COLORS[style] }), ARROW_NAMES[style] || style))) : null,
      h("div", { class: "controls" },
        h("button", { onclick: () => previousMoment && goTo(previousMoment.ply + 1), disabled: previousMoment ? undefined : "", "aria-label": "К прошлому ключевому моменту" }, "« Момент"),
        h("button", { onclick: () => goTo(ply - 1), "aria-label": "Назад" }, "◀"),
        h("button", { onclick: () => goTo(ply + 1), "aria-label": "Вперёд" }, "▶"),
        h("button", { onclick: () => nextMoment && goTo(nextMoment.ply + 1), disabled: nextMoment ? undefined : "", "aria-label": "К следующему ключевому моменту" }, "Момент »"),
      ),
      h("p", { class: "note" }, lineActive() ? "" : "Свой вариант: нажмите на фигуру и на поле, куда её поставить. Ходить можно за обе стороны."),
    );

    const right = h("div", {},
      h("div", { class: "spread" },
        h("button", { class: "link", onclick: () => leave(() => showRecap(game)) }, "‹ Итоги"),
        h("span", { class: "note" }, "против " + game.opponent),
      ),
      scanBlock(scan, () => { scans.delete(game.id); leave(() => showReview(game, ply)); }),
      evaluations.length ? drawGraph(evaluations, positions.length, ply, moments, goTo) : null,
      moveList,
      lineActive() ? h("div", { class: "card user-line" },
        h("span", { class: "note" }, "Свой вариант"),
        h("div", { class: "variation" }, line.map((move, index) => h("button", {
          class: index === lineCursor ? "on" : "",
          onclick: () => { lineCursor = index; lineText = ""; selected = null; render(); askEngine(); },
        }, figurine(index === 0 || move.fenBefore.split(" ")[1] === "w"
          ? move.fenBefore.split(" ")[5] + (move.fenBefore.split(" ")[1] === "w" ? ". " : "... ") + move.san
          : move.san), h("span", { class: "eval" }, move.score ? " " + shortScore(move.score) : " …")))),
        h("div", { class: "row" },
          h("button", { onclick: explainLineMove, disabled: lineCursor >= 0 ? undefined : "" }, "Объяснить ход"),
          h("button", { class: "link", onclick: () => { line = []; lineCursor = -1; lineText = ""; selected = null; render(); askEngine(); } }, "К партии"),
        ),
        lineText ? coachBlock(lineText, lineText.endsWith("…")) : null,
      ) : null,
      ply > 0 ? h("div", { class: "card" },
        h("strong", { class: "headline" }, headline(positions[ply - 1], ply > 1 ? evaluations[ply - 2] : null, evaluations[ply - 1])),
        moment ? h("p", { class: "moment", style: "color:" + MOMENT_COLORS[moment.kind] }, moment.title.split(" — ")[1] + ": " + moment.detail) : null,
        gameEnd(positions[ply - 1].fenAfter) ? h("p", {}, h("strong", {}, gameEnd(positions[ply - 1].fenAfter))) : null,
        coachBlock(coachText || "Тренер думает…", coachWaiting || !coachText),
      ) : h("div", { class: "card" }, h("strong", {}, "Начальная позиция"), h("p", { class: "note" }, "Выберите ход в списке или нажмите ▶, чтобы тренер его разобрал.")),
      variations.length ? h("div", { class: "variations" }, variations.map((variation, index) => h("div", { class: "variation" },
        h("span", { class: "note" }, variation.title + ":"),
        variation.san.map((san, step) => h("button", {
          class: shown && shown.line === index && shown.step === step ? "on" : "",
          onclick: () => { shown = { line: index, step }; selected = null; render(); },
        }, figurine(san))),
      )), shown ? h("button", { onclick: () => { shown = null; render(); } }, "Вернуться к партии") : null) : null,
    );

    app.replaceChildren(h("div", { class: "review" }, left, right));
    moveList.querySelector(".on")?.scrollIntoView({ block: "nearest" });
  }

  function onKey(event) {
    if (event.target.tagName === "INPUT") return;
    if (event.key === "ArrowLeft") goTo(ply - 1);
    if (event.key === "ArrowRight") goTo(ply + 1);
  }
  document.addEventListener("keydown", onKey);

  window.scrollTo(0, 0);
  goTo(startPly);
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
