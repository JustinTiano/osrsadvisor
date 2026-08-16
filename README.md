# OSRS Advisor

A RuneLite sidebar panel that renders quest and training recommendations from your own
**OSRS Advisor companion server** — what to do next in the Optimal quest guide, which
skill gates to close first, costed Fastest / Balanced / AFK training routes for each gate,
and a merged Grand Exchange shopping list for the steps ahead.

> **This plugin requires a companion server and does nothing without one.** It is the
> in-game front end of a personal account-planning workspace. The server software is not
> yet publicly available — watch this repository if you're interested in self-hosting it.
> Until then, installing the plugin without a server will only show a "companion server
> not reachable" notice.

## What it sends, and where

The plugin reads your client and **pushes account state to the server URL you configure**
— by default `http://localhost:8777/api/ingest`, i.e. your own machine. Nothing is sent
anywhere else, and there is no third-party service involved unless you point the URL at
one. Each push contains:

- player name (keys the server's per-account state)
- skill **xp** (never levels — xp is monotonic, so stale sources can't corrupt estimates)
- quest and achievement-diary completion
- bank, inventory and equipment contents (each individually toggleable in settings)
- Grand Exchange offers (for 4-hour buy-limit tracking)

One direction only for game data: the plugin reads the client and POSTs. It never sends
input to the game.

## The panel

The toolbar's scroll-and-compass icon opens a scrollable list of cards, two kinds
interleaved. A **training card** — "Train Woodcutting 35 → 36, for #53 Lost City" —
appears above the quest whose gate it closes, with a Fastest/Balanced/AFK pill selector,
hours and net gp per route band, and wiki/video links where the method data carries them.
A **quest card** shows the guide row: skill gates diffed against live stats, prerequisite
status and item shortfalls. An amber **"Visit a bank"** banner appears when the newest
bank snapshot is over a day old, because that is when the costing under every card has
gone quietly stale.

The shopping-cart toggle (automatic while the Grand Exchange is open) swaps the cards for
the merged buy list of everything the shown steps need, drawn against one holdings pool
so two steps never double-count the same bank stock.

**Right-click a quest card → Mark done** ticks the row in the server's guide, behind a
confirmation (with a one-shot Undo in the status bar). This is also how you tick activity
rows like Stronghold of Security, which the game's quest list omits entirely.

## Settings

| Setting | Default | Notes |
|---|---|---|
| Ingest URL | `http://localhost:8777/api/ingest` | Where your companion server listens. All data above goes to this URL and nowhere else. |
| Heartbeat (minutes) | 15 | Push even when nothing changed, so the server can tell live from stale. 0 disables. |
| Debounce (seconds) | 20 | Minimum gap between pushes. |
| Send bank / inventory & equipment / GE offers / skill xp | on | Each category individually toggleable. |

Pushes fire on login, level-up, bank/inventory/equipment change, GE offer change and
quest-point change, debounced.

## Building and development

See [DEVELOPMENT.md](DEVELOPMENT.md).

## License

[BSD 2-Clause](LICENSE).
