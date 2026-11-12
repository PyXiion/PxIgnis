# Arena tutorial code

The pages are in `site/src/content/docs/tutorial/`; the code they show lives here.

- `arena/NN-*.lua`: the complete `arena.lua` after chapter NN (the "checkpoint" at the end of each page,
  embedded with a `?raw` import). `08-rewards.lua` is the second script from chapter 8.
- `check-snippets.py`: every line of a ```` ```lua ```` block in page NN must appear in `arena/NN-*.lua`, so
  the text cannot drift from the checkpoints. Mark an intentionally different block ```` ```lua nocheck ````.
- `test/`: a fake PxIgnis API (`mock.lua`) and scripts that play the arena through with it
  (`smoke.lua` for each checkpoint, `scenario.lua` for the finished game).
- `test.sh`: runs all of the above plus `lua-language-server --check` against `lua-types/`. CI runs it in
  the `tutorial` job.

When you change a chapter, change its checkpoint and every later one the same way, then run `test.sh`.
