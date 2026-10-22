module example

<fold text='/**...*/'>/**
 * A documented signature
 */</fold>
sig State {}

<fold text='/*...*/'>/* a block
   comment */</fold>
/* single line */
/** single line */
// a line comment
-- another line comment
pred step[s: State] {}
