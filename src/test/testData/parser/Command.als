run p for 3
run show { some A } for 3 but 5 Int expect 1
check noCycles for 5
run label target for exactly 2 A, 3..4 B, 10 steps
run {}
run first => check second
check
