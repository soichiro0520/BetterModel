# BetterModel ベンチマーク環境

このフォークの達成目標:
**TPS 20 を維持したまま、1万ポリゴン級モデルを 30〜50体、視界内・処理対象として同時稼働させる。**

重要な前提(調査結果 `docs/performance/INVESTIGATION.md` 参照):
メッシュ形状・UV はロード時にリソースパックへ焼き込まれるため、**サーバー負荷は
「ポリゴン数」ではなく「ボーン数 × 体数 × 更新頻度」で決まる。**
1万ポリゴンはクライアント描画コストであり、サーバー側の比較はボーン数を変えて行う。

## 構成

```
benchmark/
  models/                 生成済みベンチモデル (.bbmodel)
  scripts/
    generate_model.py     合成モデル生成 (--cubes / --bones 指定)
    setup_server.sh       Paper サーバー構築 + プラグイン導入
    run_server.sh         JFR / async-profiler 付き起動
benchmark-plugin/         計測プラグイン (/bmbench)
```

## セットアップ

```bash
cd benchmark/scripts
python3 generate_model.py --name bench_10k_b20 --cubes 834 --bones 20
python3 generate_model.py --name bench_10k_b50 --cubes 834 --bones 50
./setup_server.sh 1.21.8      # Paper DL + gradlew build + plugins 配置
./run_server.sh               # JFR 常時記録付きで起動
```

## 計測コマンド (`/bmbench`)

| コマンド | 説明 |
|---|---|
| `/bmbench spawn <model> <count> [spacing]` | 実行プレイヤー前方にグリッド状に count 体スポーン(AI無効ゾンビ + モデル + ループアニメ再生) |
| `/bmbench clear` | ベンチ用エンティティ全削除 |
| `/bmbench measure <seconds> [label]` | 計測ウィンドウ実行 → コンソール/チャットに要約、`plugins/BetterModel-Benchmark/results.csv` に追記 |
| `/bmbench status` | 現在のスポーン数・ワーカースレッド数・TPS |

`measure` が収集する項目:

- **TPS(1m) / MSPT avg・p95・max** — `ServerTickStartEvent`/`ServerTickEndEvent` によるメインスレッド実測
- **BetterModel-Worker CPU 時間** — トラッカー処理は 25ms 周期の専用スレッドプールで動くため、
  メインスレッド MSPT だけでは負荷が見えない。ワーカー CPU 合計が本体
- **Server thread / Netty CPU 時間** — vanilla+プラグイン分とパケット送信系の切り分け
- **GC 回数・停止時間、JVM 全体のアロケーションレート (MB/s)**

## シナリオ・マトリクス

| ケース | モデル | 体数 | 手順 |
|---|---|---|---|
| 基準 | bench_10k_b20 | 30 | `spawn bench_10k_b20 30` → `measure 60 base30` |
| 目標上限 | bench_10k_b20 | 50 | `spawn bench_10k_b20 50` → `measure 60 base50` |
| 劣化カーブ | bench_10k_b20 | 10..50 | 10体ずつ追加し各段で `measure 30 curveN` |
| ボーン数比較 | bench_10k_b20 vs bench_10k_b50 | 30 | 各々 `measure 60 bones20` / `measure 60 bones50` |
| カリング効果 | bench_10k_b20 | 50 | 全体を視界に入れた状態と、後ろを向いた状態で各々 measure |
| LOD効果 | bench_10k_b20 | 50 | 至近(<16m)と遠距離(>48m)で各々 measure |

Before/After 比較は `performance:` 設定 (config.yml) の on/off で行う:

```yaml
performance:
  animation-culling: true   # false にすると視線外も全計算(=upstream相当)
  lod: true                 # false にすると距離LOD無効
```

## ボトルネック切り分けの目安

- `cpu_bm_worker_ms` が支配的 → ボーン変換・キーフレーム処理が犯人。LOD間隔を上げる/ボーン数を減らす
- `cpu_netty_ms` が支配的 → パケット送信。`packet-bundling-size` 調整、LODで送信レート減
- `gc_count`/`alloc_mb_s` が高い → 毎tickのオブジェクト生成。JFR の Allocation イベントで発生源特定
- MSPT が高いのに worker CPU が低い → BetterModel 以外(vanilla エンティティ tick 等)が犯人

## JFR / async-profiler

- JFR は `run_server.sh` で常時記録 (`bench.jfr`)。`jfr print --events jdk.SocketWrite bench.jfr`
  で実送信バイト数、`jdk.ObjectAllocationSample` でアロケーション源を確認できる
- async-profiler は `PROFILER=/path/libasyncProfiler.so ./run_server.sh` で起動時ロード、
  または実行中に `asprof -d 60 -f flame.html <pid>`。
  `BetterModel-Worker-*` スレッドに絞ると変換パイプラインの火炎グラフが得られる
