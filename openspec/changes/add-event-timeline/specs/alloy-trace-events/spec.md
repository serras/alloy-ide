# Spec Delta

## Purpose

Determines which predicates of an Alloy model are its events, and which instances of those events hold at each transition of a trace. This lets the instance viewer label transitions without the model needing the book's event-depiction idiom.

## ADDED Requirements

### Requirement: Event predicates detected from the trace fact
For a trace result, the event predicates SHALL by default be the predicates called as disjuncts of a fact of the form `always (...)`, looking through `some` quantifiers, `let` and nested disjunctions. A predicate whose body is itself such a disjunction of calls SHALL be replaced by the predicates it calls.

#### Scenario: Leader election trace fact
- **WHEN** a model has `fact events { always (stutter or some n : Node | node_acts[n]) }` and `node_acts[n]` is `initiate[n] or (some i : Id | send[n, i]) or (some i : Id | process[n, i])`
- **THEN** the detected event predicates are `stutter`, `initiate`, `send` and `process`, and not `node_acts`

#### Scenario: Several groups of disjuncts
- **WHEN** the file sharing model's `transitions` fact is `always ((some f : File | upload[f] or delete[f] or restore[f]) or (some f : File, t : Token | share[f, t]) or (some t : Token | download[t]) or empty or stutter)`
- **THEN** the detected event predicates are `upload`, `delete`, `restore`, `share`, `download`, `empty` and `stutter`

#### Scenario: No trace fact
- **WHEN** no fact of the model has that form
- **THEN** no event predicates are detected

#### Scenario: Recursive grouping
- **WHEN** a grouping predicate calls itself, directly or indirectly
- **THEN** detection ends without error, and the predicate in the cycle is kept as an event

### Requirement: Event predicates chosen by the user
The user SHALL be able to replace the detected event predicates of a file with any set of its model's predicates, and to go back to the detected ones. The choice SHALL be kept per file across IDE restarts. Chosen predicates that no longer exist in the model SHALL be ignored.

#### Scenario: Unchecking a detected event
- **WHEN** the leader election model is shown and the user unchecks `stutter`
- **THEN** `stutter` is no longer an event of that file, also for the results opened later and after restarting the IDE

#### Scenario: Back to detection
- **WHEN** the user chooses to reset the events of that file
- **THEN** the event predicates are the detected ones again

#### Scenario: Renamed predicate
- **WHEN** the user chose `send` and then renames it to `transmit` in the model
- **THEN** `send` is ignored, and `transmit` is an event only if it is detected or chosen

### Requirement: Event instances of a transition
For the transition from state *i* of a trace to its successor, the event instances SHALL be each event predicate with every tuple of arguments, of the predicate's parameter types, for which it holds at state *i*, in model order. A parameterless predicate appears by name. Atoms are named as in the Text tab.

#### Scenario: Event with parameters
- **WHEN** at state 3 of a leader election trace `send[Node$1, Id$2]` holds and no other event does
- **THEN** the events of the transition from state 3 are `send[Node$1, Id$2]`

#### Scenario: Parameterless event
- **WHEN** at state 7 only `stutter` holds
- **THEN** the events of the transition from state 7 are `stutter`

#### Scenario: Several events at once
- **WHEN** a model declares `pred tick` before `pred move[x : X]`, and at state 4 `tick`, `move[X$0]` and `move[X$1]` all hold
- **THEN** the events of that transition are `tick`, `move[X$0]` and `move[X$1]`, in the order of the model's predicates and then of the analyzer's tuples

#### Scenario: No event holds
- **WHEN** no event predicate holds at state 2
- **THEN** the transition from state 2 has no events, and it is reported as "no event"

#### Scenario: Loop-back transition
- **WHEN** a trace of 4 states loops back to state 1
- **THEN** the transition from state 3 to state 1 also has its events

### Requirement: Predicates that cannot be events
A chosen predicate whose parameters are not all single atoms of a signature (for example, a parameter of type `set Node` or `Node -> Node`) SHALL not be evaluated. The Events dropdown SHALL say why it is not offered, and evaluating the others SHALL not be affected.

#### Scenario: Set parameter
- **WHEN** the model has `pred move[ns : set Node]`
- **THEN** the Events dropdown shows `move` disabled, with a tooltip saying that events can only have single-atom parameters

### Requirement: Event evaluation does not block the IDE
The event instances SHALL be computed in the background when a trace is shown. The tool window SHALL show the trace before they are known, and SHALL fill in the events when they are. A failure to evaluate a predicate SHALL leave its events out and SHALL be shown in the tooltip, without affecting the rest of the tool window.

#### Scenario: Large trace
- **WHEN** a trace of 20 states with 6 event predicates is opened
- **THEN** the graphs are shown right away, and the transitions show their events once they have been computed

#### Scenario: Evaluator error
- **WHEN** evaluating `process` throws an analyzer error
- **THEN** the transitions show the other events, and their tooltip says that `process` could not be evaluated, with the analyzer's message
