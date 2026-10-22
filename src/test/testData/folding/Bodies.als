module example

sig State <fold text='{...}'>{
  next: lone State
}</fold>

sig Short { f: set State }

pred step[s: State] <fold text='{...}'>{
  all x: State | {
    x in s.next
  }
}</fold>

fact {}

run step for 3
