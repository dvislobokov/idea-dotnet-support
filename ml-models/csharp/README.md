# C# models of the ML completion engine (trained 2026-10-07 on the server, see idea-ml-completion CHANGELOG e15 / e18)

| file | what | size | sha256 (prefix) | use |
|---|---|---|---|---|
| `cs31m-e2-lr2e3.cml` | own transformer cs31m (d512 × 8, 31 M params, int8), BPE 16k, SPM/PSM FIM, 5.65 G tokens, lr 2e-3 / 0.5 M batch | 31.2 MB | `02b0e252bf2827d1` | **inline (grey-text) completion** via `NnCompletion` |
| `cs-16384.bpe` | the BPE vocabulary of that model (`tokenizerSha256` in the .cml meta must match: `bc9b48da…`) | 137 KB | `bc9b48daedd84612` | loaded with the model |
| `e15-a.cml` | n-gram LM, order 5, MKN, 24-bit fingerprints (ppl 5.3 on the test fold) | 32.7 MB | `5c8b6e466bea6124` | ranker feature `lm_logprob`, per-file cache; optional |
| `e15-a-rank.cml` | **proxy** ranker (linear, trained on synthetic candidate lists from the corpus) | 1.7 KB | `4313a6c148c35858` | pipeline debugging only — NOT for users: on Go the same kind of proxy ranker scored below the plugin's own ordering on real lists (MRR 0.518 vs 0.527); a real C# ranker needs lists exported from this plugin (e18) |

Measured quality of `cs31m-e2-lr2e3` (3 000 test positions, SPM prompt, token healing, repetition guard — the behaviour
`NnCompletion.complete()` implements): rest of line exact 50.2 % of all positions, 62.6 % when ≤ 8 tokens remain; at
`confProd ≥ 0.8` 20 % of positions get a suggestion and 97 % of those lines are exactly right. Repositories created
after 2026-05 (never seen by anything): 40.9 %.

Latency with the native kernels (8 threads, x86 AVX-512 / Apple M1): cold 1 500-token prefill ≈ 150 ms, incremental line
while typing ≈ 25 ms; scalar Kotlin fallback ≈ 3.3× slower. Memory: ~100 MB per loaded model (weights + packed q8 + KV cache).

Source of truth and training: https://github.com/dvislobokov/idea-ml-completion (`csharp/models/` on the training server).
