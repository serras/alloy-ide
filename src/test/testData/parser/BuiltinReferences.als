fun plus [n1, n2: Int] : Int { n1 fun/add n2 }

sig Elem { next: lone Elem }

fact {
  pred/totalOrder[Elem, Elem, next]
}
