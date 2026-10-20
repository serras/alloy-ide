sig A { f: set A }
pred p[x: A] { some x.f }
open util/ordering[A] as ord
fact { ord/first in this/A and some @f }
