module example
<fold text='open ...'>open util/ordering[State]
// comments between imports keep the group together
open util/integer</fold>

<fold text='/**...*/'>/**
 * A documented signature
 */</fold>
sig State <fold text='{...}'>{
  next: lone State
}</fold>

sig Short { f: set State }

<fold text='/*...*/'>/* a block
   comment */</fold>
/* single line */
pred step[s: State] <fold text='{...}'>{
  all x: State | {
    x in s.next
  }
}</fold>

open util/boolean
fact {}
open util/relation

run step for 3
