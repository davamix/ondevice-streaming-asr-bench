# Corpus sources

Fetched by `scripts/fetch_corpus.py` at the revisions pinned below.
Audio itself is gitignored -- rebuild it with `scripts/build_corpus.py`.

| Key | Repo | Revision | File | Lang | Licence |
|---|---|---|---|---|---|
| `fleurs_en` | [`google/fleurs`](https://huggingface.co/datasets/google/fleurs) | `70bb2e84b976b7e960aa89f1c648e09c59f894dd` | `parquet-data/en_us/test-00000-of-00001.parquet` | en | CC-BY-4.0 |
| `fleurs_es` | [`google/fleurs`](https://huggingface.co/datasets/google/fleurs) | `70bb2e84b976b7e960aa89f1c648e09c59f894dd` | `parquet-data/es_419/test-00000-of-00001.parquet` | es | CC-BY-4.0 |
| `librispeech_other` | [`openslr/librispeech_asr`](https://huggingface.co/datasets/openslr/librispeech_asr) | `71cacbfb7e2354c4226d01e70d77d5fca3d04ba1` | `all/test.other/0000.parquet` | en | CC-BY-4.0 |

## Why these

FLEURS `en_us` and `es_419` are the same corpus recorded the same way in both
languages, so an English-vs-Spanish comparison is not confounded by domain or
recording conditions. LibriSpeech `test-other` adds the noisy-English stress case
that FLEURS's clean read speech does not cover.

Both are CC-BY-4.0, so a small curated sample could be redistributed with
attribution if reproducibility ever needs it.

## Attribution

- FLEURS: Conneau et al., *FLEURS: Few-shot Learning Evaluation of Universal
  Representations of Speech* (Google, 2022). CC-BY-4.0.
- LibriSpeech: Panayotov et al., *LibriSpeech: an ASR corpus based on public domain
  audio books* (ICASSP 2015). CC-BY-4.0.
