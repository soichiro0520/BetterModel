# フォーク運用ガイド

このリポジトリは [toxicity188/BetterModel](https://github.com/toxicity188/BetterModel) の
パフォーマンス特化フォーク。upstream 追従はせず、必要な修正のみ cherry-pick する。

## upstream リモートの設定

```bash
git remote add upstream https://github.com/toxicity188/BetterModel.git
git fetch upstream v3
```

## upstream との差分確認

```bash
git log --oneline v3..upstream/v3          # upstream にあってフォークにないもの
git log --oneline upstream/v3..HEAD        # フォーク独自コミット
```

## 重要修正の取り込み(cherry-pick)

Minecraft バージョン対応・バグ修正など必要なコミットだけ取り込む:

```bash
git fetch upstream v3
git cherry-pick <commit>
```

コンフリクトが出やすい場所(フォークで変更済み):

| ファイル | フォークでの変更 |
|---|---|
| `api/.../tracker/Tracker.java` | updater への LOD/カリング組込み、BundlerSet.send のシグネチャ |
| `api/.../animation/AnimationStateHandler.java` | `tick(int frames, ...)` キャッチアップ走行 |
| `api/.../bone/RenderedBone.java` | `tick(frames)` / `sendTransformation(..., minDuration)` |
| `api/.../data/renderer/RenderPipeline.java` | `tick(bundler, frames, minDuration)` / `tickIdle` / `flushTransformation` / `viewedPlayerList` |
| `nms/*/EntityData.kt` | interpolation duration の差分送信(全バージョン同一パッチ) |
| `core/src/main/resources/config.yml` ほか config | `performance:` セクション |

`nms/*/EntityData.kt` は 7 モジュールでバージョン文字列以外同一。upstream 側の変更を
取り込む場合は 1 モジュール分を解決してから他へ sed でコピーするのが速い:

```bash
for m in v1_21_R3 v1_21_R4 v1_21_R5 v1_21_R6 v1_21_R7 v26_R1; do
  sed "s/v26_R2/$m/g" nms/v26_R2/.../v26_R2/EntityData.kt > nms/$m/.../$m/EntityData.kt
done
```

## フォーク独自の変更一覧

- 計算カリング / 距離 LOD / パケット差分強化 — `docs/performance/INVESTIGATION.md` §5
- ベンチマーク環境 — `benchmark/` + `benchmark-plugin/`
- 設定 — `performance:` セクション(`api/.../config/PerformanceConfig.java`)
