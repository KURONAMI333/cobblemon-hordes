# Cobblemon Horde Battles

A Cobblemon addon. Wild Pokemon sometimes come at you two at a time instead of one — a 2-on-2 wild
double battle, not Gen 6's five-Pokemon Horde Encounter despite the name. Whittle the pair down and
catch the one left standing. Design is in [DESIGN_COMPILE.md](DESIGN_COMPILE.md).

Multiloader (NeoForge + Fabric), Minecraft 1.21.1, Cobblemon 1.7.3. Both loaders build; the horde
loop is exercised headlessly on both (`:neoforge:runGameTestServer`, `:fabric:runGametest`, see
below).

## What it does

`HordeTrigger` (`common/`) subscribes to `CobblemonEvents.BATTLE_STARTED_PRE`, the single choke point
every Cobblemon battle passes through before it starts. When an ordinary wild encounter is eligible —
the wild side is one unexcluded Pokemon and the player's party can field two — it rolls
`HordeConfig.hordeChancePercent` (15% by default, provisional, see `HordeConfig`'s doc comments). If
that hits, the single battle is cancelled and a `GEN_9_DOUBLES` battle starts instead with a
`HordeBattleActor` holding two Pokemon of the same species: a real neighbour standing within 12 blocks
if there is one, otherwise a clone that comes out to join it (`HordeTrigger.assembleHorde`).

Species that never trigger a horde: `pokemon.isLegendary() || isMythical() || isUltraBeast()`
(`HordeTrigger.isExcluded`) — read off the species' own labels, not a hardcoded name list, so a
species another addon adds is eligible (or excluded) the moment it's installed. If the player's party
can't field two living Pokemon, the trigger backs off and the ordinary single encounter runs
(`HordeBattles.partyCanFaceHorde`).

Capturing follows Cobblemon's own rule, unmodified except for the ball-throw gate itself:
`EmptyPokeBallEntityMixin` only lifts the "can't throw at 2+ wild Pokemon" check for wild (`PvW`)
battles, and only down to the last one standing — trainer and PvP doubles are untouched.

## Build and test

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.10.7-hotspot"
./gradlew :neoforge:build
./gradlew :neoforge:runGameTestServer     # headless; exit code is the verdict
./gradlew :fabric:runGametest             # same loop, Fabric's own test runner
```

`runGameTestServer` (and Fabric's `runGametest`) run the whole horde loop against a mock player: the
battle starts, turns resolve, the battle survives a wild Pokemon fainting, the last one is caught, and
the horde flees when the player walks off. Read the `[HORDELOOP]` (NeoForge) or `[FABRICHORDE]`
(Fabric) lines for the details of a run.

Both `:neoforge:runClient` and `:neoforge:runGameTestServer` stage Cobblemon into `run/mods` and
prepare `run/showdown` first (see below). Launch the client through
`../_tools/runclient_fresh.sh <this directory> :neoforge:runClient` - naming the task matters,
because a bare `runClient` in a multiloader project starts Fabric and NeoForge at the same time.

## Showdown errors are visible here, unlike everywhere else

**Cobblemon's Showdown bridge silently swallows every asynchronous JS exception.** The `for await`
IIFE in `showdown/index.js` has no `.catch()`, so a Showdown-side `TypeError` produces no log line,
no crash and no message - the battle just stops progressing. Anyone debugging a stalled battle
without the fix is debugging blind.

The `prepareShowdown` Gradle task (`neoforge/build.gradle`) handles it. It unbundles `showdown.zip`
out of the Cobblemon jar into `run/showdown` itself, adds the missing `.catch()`, and writes the
matching `showdown.json` so Cobblemon's own `GraalShowdownUnbundler` sees a current install and
leaves it alone. It hangs off the same `prepare*Run` hook as `copyDevMods`, so every run gets it.

- Async Showdown failures then appear as `[SHOWDOWN-ASYNC-ERROR] <battleId> <stack>`.
- When Cobblemon ships a newer Showdown the versions stop matching, the task re-extracts from the
  new jar and re-applies the patch. Nothing to do by hand.
- If Showdown's bridge is ever restructured, the task fails loudly rather than leaving a silently
  unpatched install behind.

## What is verified, and what is not

Verified headlessly (`HordeLoopGameTests`):

| | |
|---|---|
| Battle start | A real `PlayerBattleActor` versus one wild actor holding two Pokemon, `GEN_9_DOUBLES` |
| Turn resolution | Four turns, three player choices, no deadlock |
| A wild Pokemon fainting | The battle carries on with one horde member left |
| Capture | The last one goes into a ball (needs `EmptyPokeBallEntityMixin`; without it Cobblemon answers `You can only capture wild Pokemon in a 1v1 battle`) |
| Flee | Walking away ends the battle through `checkFlee` |
| The party gate | A party that cannot field two living Pokemon does not get a horde |
| Trigger | `hordeTriggerSwapsEligibleWildEncounters` — an eligible wild encounter swaps to a horde; an ineligible one (party too small, chance rolled 0, or a legendary leader) is left alone |

Not verified:

- The interaction between the horde's follow AI and `fight-or-flight-reborn`'s wild-Pokemon Brain
  mixins — that addon rewrites wild AI at a level this mod doesn't touch, and the two haven't been run
  together yet.
- Experience distribution when one of the *player's* Pokemon faints mid-battle. The award-per-defeated
  check above only covers wild Pokemon fainting; `awardExperienceToFaintedPokemon`'s ordering rules
  make the player-faints case a different expected-value calculation that hasn't been measured yet.
- One non-reproducing case: a single run where a player-participating battle got no response from
  Showdown at all, while an AI-only control battle in the same run passed normally. Seven follow-up
  runs didn't reproduce it and the cause is unidentified.

Everything else in the table above is exercised headlessly by `:neoforge:runGameTestServer`, which
both loaders share through the `common/` module (the `com.cobblemon:neoforge` artifact resolves
against Mojmap, which is what let `common/` hold the Cobblemon-facing battle code instead of needing
an architectury split).

## The party gate is not optional

`HordeBattles.teamCanFaceHorde` counts **living** Pokemon, not party slots, and a party that falls
short leaves the encounter an ordinary single battle. It covers two different failures:

- **Fewer team members than active slots.** `side.active[1]` stays null and `Battle.runAction` walks
  `side.active` with no null guard, so the battle dies on an asynchronous, silent `TypeError`
  (`battle.js:2411`).
- **Two members, one fainted.** Both get packed, so the slot is filled rather than null and Showdown
  *does* start the battle - measured, it reaches turn 1. What you get is a player fielding a fainted
  Pokemon in the second slot for the whole fight.

Only the first is a crash; the gate refuses both.

## License

MIT — see [LICENSE](LICENSE). Author: KURONAMI333. Built on [Cobblemon](https://modrinth.com/mod/cobblemon).

Unofficial Cobblemon addon. Not affiliated with Cobblemon or The Pokémon Company.
