# CURRENT_TASK.md

## 作業タイトル
デバッグ画面リアルタイムオーバーレイ + BlackFrameDetector + BR角補正

## 目的・背景
伝票を大きく撮影したとき緑枠の角が画角に入りきらず透視変換が不安定になる問題を調査するため、
デバッグ画面にリアルタイムオーバーレイ表示と黒罫線ベースの補助検出器を追加する。

## 今回のタスク
- [x] BlackFrameDetector.kt 新規作成（Hough直線検出による黒枠検出）
- [x] CameraViewModel に FrameOverlayState / greenFrameOverlay / blackFrameOverlay StateFlow 追加
- [x] CameraViewModel に updateGreenOverlay / shouldRunBlackFrameDetection / updateBlackOverlay メソッド追加
- [x] CameraScreen.kt の analyzeFrame() にオーバーレイ更新処理追加（最小限の変更）
- [x] DebugCaptureScreen.kt に FrameDetectionOverlay Composable 追加
- [x] BUILD SUCCESSFUL 確認
- [x] ReceiptRow に dateBounds フィールド追加 → デバッグ画面に日付矩形表示
- [x] fitHOuter X範囲制限（セグメント端点 ±3%）→ BR角のフィット直線傾き誤差を解消
- [x] 平行四辺形則 BR 妥当性チェック追加（閾値5%）
- [x] CaptureInfo にコーナー角度・BR補正フラグ追加 → ⑥撮影情報に表示
- [x] 実機確認：コーナー角度改善・BR補正不要（精度OK）

## 完了条件
- [x] 緑枠オーバーレイが遅延なく追従する（実機確認済み）
- [x] 透視変換の精度が十分（コーナー角度が約90°・実機確認済み）
- [x] 通常撮影画面の動作に影響がない

## 進捗メモ
- BlackFrameDetector は実装済みだが、カメラスレッドをブロックするため無効化（コードは残存）
- 緑枠オーバーレイのみ表示
- 速度改善: 200ms分析の合間に50ms間隔で480px軽量更新（updateOverlayOnly）を追加 → 最大20fps
- 位置修正: FILL_CENTER式（max scaleで統一 + オフセット）に変更
- OCR結果行情報に amountBounds / dateBounds (rect) 追加表示
- デバッグテキスト保存機能追加（Documents/OCRTest/）
- BR角誤差の根本原因: fitHOuter が imgW 全列をスキャンしていたため背景緑・切り端ノイズがフィット直線を傾けていた
- 修正: セグメント端点 X範囲 ±3% に制限 → BR補正フォールバックが不要なほど直接改善

---

## 作業終了時の記録（2026-05-28）

### 今回完了したこと
- 日付列 OCR 矩形のデバッグ表示追加
- fitHOuter X範囲制限による BR コーナー精度修正（実機確認済み）
- 平行四辺形則 BR 妥当性チェック（閾値5%）
- デバッグ画面⑥撮影情報にコーナー角度・BR補正フラグ表示追加

### 未完了・中断した理由
なし（すべて完了）

### 次回セッションで最初にやること
docs/known-issues.md の BR誤差問題を解決済みに更新し、次フェーズ（複数年対応など）に進む。

### 新たに発覚した問題・制約
なし
