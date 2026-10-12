# Acceptance memory — what the plugin remembers of the user's choices (0.1.135)

The completion list "learns from me": every item chosen in a completion list of a C# file is counted, per project, and the count lifts the
item the next time it is offered in the same kind of place. This file says what is stored, where, and how the ranker uses it, so the
engine (`idea-ml-completion`) can turn it into a ranker feature later.

## What is stored

`suggest/CSharpAcceptanceMemory.kt`, a project-level `PersistentStateComponent` in the **workspace file** of the project
(`.idea/workspace.xml`, component `DotNetAcceptanceMemory`; not shared with the team, not synced by Settings Sync):

```xml
<component name="DotNetAcceptanceMemory">
  <option name="month" value="24312" />
  <option name="counts">
    <map>
      <entry key="AFTER_DOT|ToListAsync" value="7" />
      <entry key="STATEMENT_START|_logger" value="3" />
    </map>
  </option>
</component>
```

- **key** — `<kind>|<lookup string>`. The kind is the context kind of the ML ranker's schema (`ml-core` `ContextKind`:
  `AFTER_DOT`, `STATEMENT_START`, `ARGUMENT`, `TYPE_POSITION`, `ASSIGN_RHS`, `OTHER`), computed by the same code the ranker's features use
  (`CSharpMlFeatures.contextKind` over the engine's lexer tokens of the 4 KB before the start of the lookup; the identifier being typed is
  not among them). The lookup string is the item's as the list has it, trimmed, a `<>` suffix dropped, blank or longer than 80 characters
  not counted.
- **value** — how many times chosen, capped at 1000.
- **month** — `year × 12 + month` of the last decay. On the first use of a new month every count is halved once per month gone
  (`count >> months`); what reaches zero is dropped. So a habit of last year weighs little, and the file does not grow forever.
- At most 2000 keys; past that the rarest quarter is forgotten.

Recording: a `LookupManagerListener` (`CSharpAcceptanceListener`) adds a `LookupListener` to every lookup of a C# file; `itemSelected`
is the real acceptance (Enter, Tab, a commit character — not a cancelled list), the kind of the place is computed on a pooled thread.
Nothing is recorded, weighed or even read while Settings | .NET → Behavior → "Remember what I choose in the completion list" is off;
"Forget the Choices" there empties the memory of the project.

## How it is used

1. **Without the ML ranker** (the plain build, or the ranker off): the completion weigher `dotnetAcceptedBefore`
   (`CSharpAcceptedBeforeWeigher`, `after prefix, before dotnetSuggestionStats`) orders the rows of one priority group by the count,
   larger first. The priority of the plugin's rules (expected type, locals, members, types, keywords) stays above it: the memory
   reorders inside a group, never across groups.
2. **With the ML ranker** (`CSharpMlCompletionRanker`, a build with the models): `weight × ln(1 + count)` is added to the ranker's
   score of the candidate. The weight is Settings | .NET | ML completion → "Weight of my earlier choices", default **0.3**: on the scale of
   `e18-rank` a close call is 0.2–0.5 of score apart and a clear loss (an unimported type, a keyword where a value goes) 1.5 or more,
   so three choices (0.3 × ln 4 = 0.42) win the close call and ten (0.72) do not win the clear loss. 0 turns the bonus off.

## API

```kotlin
val memory = CSharpAcceptanceMemory.getInstance(project)
memory.count(ContextKind.AFTER_DOT, "ToListAsync")   // 7
memory.entries()                                      // Map<"kind|lookup", count>, a snapshot
memory.record(kind, lookupString)                     // what the listener calls
memory.reset()
CSharpAcceptanceMemory.contextOf(text, caret)         // the kind of a place
CSharpAcceptanceMemory.bonus(count, weight)           // weight × ln(1 + count)
```

## For the engine

A ranker feature `accepted_log = ln(1 + count(kind, lookup))` can be added to the language block of `CSharpMlFeatures` once the exported
lists carry it; the export (`mlDataset`) runs without a user, so the feature would have to be simulated from the order of the positions
of one repository (what was "chosen" earlier in the same file counts), which is what `file_freq_log` already approximates. Until then the
additive bonus above is the integration, and the weight is the one knob.
