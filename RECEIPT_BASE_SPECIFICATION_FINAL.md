# Receipt OCR台紙仕様書（最終版）

## 概要

農協精算伝票のOCR処理用台紙仕様。伝票を台紙の上に置いてスマートフォンで撮影し、ArUcoマーカー4点を用いた射影変換（warpPerspective）により、歪みを補正した正規化画像を生成する。その後、定義済みROIからOCRを行う。

本仕様は **台紙設計・撮影・画像補正・ROI抽出・OCR** までを一貫して定義する最終確定版である。

---

## 1. 台紙全体仕様

### 用紙

* **サイズ**: A4 横向き
* **実寸**: 297 × 210 mm
* **印刷倍率**: 100%（拡大縮小なし）

---

## 2. 伝票配置エリア

### 伝票サイズ

* **幅**: 211 mm
* **高さ**: 148 mm

### 配置位置

* **A4用紙の完全中央**
* **横余白**: (297 − 211) ÷ 2 = **43 mm**
* **縦余白**: (210 − 148) ÷ 2 = **31 mm**

### 座標（A4左上を原点 (0,0) とする）

| 位置 | X (mm) | Y (mm) |
| -- | ------ | ------ |
| 左上 | 43     | 31     |
| 右上 | 254    | 31     |
| 右下 | 254    | 179    |
| 左下 | 43     | 179    |

---

## 3. ArUcoマーカー仕様

### 基本仕様

* **Dictionary**: `DICT_4X4_50`
* **サイズ**: 25 × 25 mm
* **数量**: 4 個

### 使用点

* 各マーカーの **4隅の平均点（中心点）** を射影変換の基準点として使用する。
* 四隅個別点ではなく中心点を用いることで、印刷滲み・部分欠け・遠近歪みに対する数値安定性を高める。

### ID割り当て（回転耐性確保）

| 位置 | ID | 役割   |
| -- | -- | ---- |
| 左上 | 0  | 原点   |
| 右上 | 1  | X軸方向 |
| 右下 | 2  | 対角   |
| 左下 | 3  | Y軸方向 |

※ IDにより上下左右を確定できるため、180°回転して撮影された場合でも復帰可能。

---

## 4. ArUcoマーカー配置

### 配置方針

* **台紙端から中心まで**: 25 mm
* **4点対称配置**
* **マーカー端から台紙端まで**: 12.5 mm（25mm ÷ 2）

### 中心座標（mm、A4左上原点）

| ID     | X   | Y   |
| ------ | --- | --- |
| 0 (左上) | 25  | 25  |
| 1 (右上) | 272 | 25  |
| 2 (右下) | 272 | 185 |
| 3 (左下) | 25  | 185 |

すべてのマーカーは **台紙内に完全に収まり、切れない** ことを保証する。

---

## 5. 位置合わせマーク（▲）

### 目的

* 人手による伝票配置を安定させるための視覚的ガイド

### 仕様

* **形状**: 三角形
* **サイズ**: 約 8 mm
* **色**: 黒（70%程度）
* **数量**: 4 個

### 配置

* 伝票配置エリアの4隅
* 正しく伝票を置いた場合、▲は完全に隠れる

---

## 6. 撮影条件

* **カメラ**: CameraX
* **解像度**: FHD（1920 × 1080）
* **撮影対象**: 台紙全体がフレーム内に収まること

※ FHD + A4全体撮影でも、warp後 8 px/mm 以上を確保でき、OCRに十分な解像度となる。

---

## 7. 射影変換（warpPerspective）

### 出力画像仕様

* **サイズ**: 2400 × 1700 px（横向き）
* **スケール**: 約 8.1 px/mm

### 設計意図

* 出力画像は「A4実寸スケールの仮想キャンバス」とする
* ArUcoの mm 座標を px に換算して dstPoints とする

### 処理手順

1. **ArUco検出**
   ```kotlin
   val arucoDict = Aruco.getPredefinedDictionary(Aruco.DICT_4X4_50)
   val detector = ArucoDetector(arucoDict, detectorParams)
   detector.detectMarkers(grayImage, corners, ids, rejected)
   ```

2. **マーカー中心点算出**
   ```kotlin
   fun getMarkerCenter(corners: MatOfPoint2f): Point {
       val pts = corners.toArray()
       val centerX = pts.map { it.x }.average()
       val centerY = pts.map { it.y }.average()
       return Point(centerX, centerY)
   }
   ```

3. **ID順にソート**
   ```kotlin
   // ID 0,1,2,3 の順に並べる
   val sortedCenters = markers.sortedBy { it.id }.map { it.center }
   ```

4. **射影変換**
   ```kotlin
   val srcPoints = MatOfPoint2f(*sortedCenters.toTypedArray())
   val dstPoints = MatOfPoint2f(
       Point(25.0 * 8.1, 25.0 * 8.1),     // ID 0: 左上 (202.5, 202.5)
       Point(272.0 * 8.1, 25.0 * 8.1),    // ID 1: 右上 (2203.2, 202.5)
       Point(272.0 * 8.1, 185.0 * 8.1),   // ID 2: 右下 (2203.2, 1498.5)
       Point(25.0 * 8.1, 185.0 * 8.1)     // ID 3: 左下 (202.5, 1498.5)
   )
   val matrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
   Imgproc.warpPerspective(srcImage, dstImage, matrix, Size(2400.0, 1700.0))
   ```

---

## 8. ROI定義（warp後）

### 伝票全体ROI

```kotlin
val slipROI = Rect(
    x = (43 * 8.1).toInt(),      // 348 px
    y = (31 * 8.1).toInt(),      // 251 px
    width = (211 * 8.1).toInt(), // 1709 px
    height = (148 * 8.1).toInt() // 1199 px
)
```

### 列OCR用ROI（例）

伝票を左右2列に分割する場合:

```kotlin
// 物理枠から10px内側にオフセット
val inset = 10

// 右列（取引日・商品名）
val rightColumnROI = Rect(
    x = 348 + inset,
    y = 251 + inset,
    width = 855 - (inset * 2),  // (211mm ÷ 2) × 8.1
    height = 1199 - (inset * 2)
)

// 左列（税込金額・分類計）
val leftColumnROI = Rect(
    x = 348 + 855 + inset,
    y = 251 + inset,
    width = 855 - (inset * 2),
    height = 1199 - (inset * 2)
)
```

---

## 9. エラーハンドリング

### マーカー検出

* マーカー検出数 < 4 → **再撮影指示**
* マーカーが歪んでいる → **警告表示**

### 射影変換

* warp後の画像が傾いている → **再処理**
* 変換行列が特異 → **再撮影指示**

### 検証コード例

```kotlin
// マーカーの正方形性チェック
fun validateMarkerSquareness(corners: MatOfPoint2f, threshold: Double = 0.15): Boolean {
    val pts = corners.toArray()
    val side1 = distance(pts[0], pts[1])
    val side2 = distance(pts[1], pts[2])
    val ratio = maxOf(side1, side2) / minOf(side1, side2)
    return ratio < (1.0 + threshold) // 15%以内
}
```

---

## 10. 印刷・運用上の注意

### プリンター設定

* ✅ **倍率**: 100%（拡大縮小なし）
* ✅ **余白補正**: OFF
* ✅ **用紙サイズ**: A4
* ✅ **向き**: 横

### 品質確保

* ArUco周囲 5mm 以内は無印刷領域を確保
* マーカーは高解像度で印刷（滲み・かすれ防止）
* 用紙は平滑な白色用紙を使用
* 用紙の反り・折れに注意

---

## 11. 座標系の整理

### PDF座標系（生成時）

* **原点**: 左下
* **Y軸**: 上向き正

### OpenCV座標系（処理時）

* **原点**: 左上
* **Y軸**: 下向き正

**重要**: 本仕様書の数値は **すべて OpenCV座標系（左上原点）** で記載する。

---

## 12. デバッグ用の検証方法

### ArUco検出確認

```kotlin
// 検出されたマーカーを画像上に描画
Aruco.drawDetectedMarkers(debugImage, corners, ids)

// 中心点を描画
corners.forEach { corner ->
    val center = getMarkerCenter(corner)
    Imgproc.circle(debugImage, center, 5, Scalar(0.0, 0.0, 255.0), -1)
}
```

### warp後の検証

```kotlin
// 伝票エリアを赤枠で表示
Imgproc.rectangle(warpedImage, slipROI, Scalar(0.0, 0.0, 255.0), 3)

// 列分割を緑線で表示
val centerX = slipROI.x + slipROI.width / 2
Imgproc.line(warpedImage, 
    Point(centerX.toDouble(), slipROI.y.toDouble()),
    Point(centerX.toDouble(), (slipROI.y + slipROI.height).toDouble()),
    Scalar(0.0, 255.0, 0.0), 2
)
```

---

## 13. 期待精度

### 位置精度

* **ArUco検出**: サブピクセル精度
* **射影変換**: ±2px 程度
* **ROI抽出**: 物理的な枠線との誤差 ±5px以内

### OCR精度

* **文字認識**: 95%以上（ML Kit Text Recognition）
* **再撮影率**: 5%以下

---

## 付録: 座標一覧表

### A4台紙上の重要座標（mm、左上原点）

| 要素           | X (mm) | Y (mm) | 備考   |
| ------------ | ------ | ------ | ---- |
| ArUco ID0 中心 | 25     | 25     | 左上   |
| ArUco ID1 中心 | 272    | 25     | 右上   |
| ArUco ID2 中心 | 272    | 185    | 右下   |
| ArUco ID3 中心 | 25     | 185    | 左下   |
| 伝票左上         | 43     | 31     |      |
| 伝票右上         | 254    | 31     |      |
| 伝票右下         | 254    | 179    |      |
| 伝票左下         | 43     | 179    |      |

### warp後の座標（px、2400×1700出力）

| 要素           | X (px) | Y (px) | 備考      |
| ------------ | ------ | ------ | ------- |
| ArUco ID0 中心 | 203    | 203    | 左上      |
| ArUco ID1 中心 | 2203   | 203    | 右上      |
| ArUco ID2 中心 | 2203   | 1499   | 右下      |
| ArUco ID3 中心 | 203    | 1499   | 左下      |
| 伝票左上         | 348    | 251    |         |
| 伝票右下         | 2058   | 1450   |         |
| 伝票サイズ        | 1710   | 1199   | W × H   |

---

**作成日**: 2024-12-17  
**対象プロジェクト**: ReceiptOCR  
**想定デバイス**: Android (Kotlin)  
**本仕様書は最終版とし、台紙制作・実装・運用の基準とする。**
