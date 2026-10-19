fact {}
fact acyclic { no x: Node | x in x.^next }
fact "string name" { some Node }
