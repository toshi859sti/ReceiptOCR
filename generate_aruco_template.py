#!/usr/bin/env python3
"""
ArUcoマーカー付きB_BLOCKテンプレートPDF生成スクリプト

新しいマーカー配置（端から15mm）で、カメラをより近づけて撮影できるようにします。
"""

import cv2
import numpy as np
from reportlab.lib.pagesizes import A4
from reportlab.lib.units import mm
from reportlab.pdfgen import canvas
from reportlab.lib.utils import ImageReader
from io import BytesIO

# マーカー設定
MARKER_SIZE_MM = 25.0
MARKER_SIZE_PX = 200  # 生成時の解像度

# A4サイズ（mm）
A4_WIDTH_MM = 297
A4_HEIGHT_MM = 210

# マーカー中心座標（mm、A4左上原点）- 新しい配置（端から15mm）
MARKERS = [
    {"id": 0, "x": 15.0, "y": 15.0},      # 左上
    {"id": 1, "x": 282.0, "y": 15.0},     # 右上
    {"id": 2, "x": 282.0, "y": 195.0},    # 右下
    {"id": 3, "x": 15.0, "y": 195.0},     # 左下
]

def generate_aruco_marker(marker_id, dictionary_type=cv2.aruco.DICT_4X4_50):
    """ArUcoマーカー画像を生成"""
    aruco_dict = cv2.aruco.getPredefinedDictionary(dictionary_type)
    marker_image = cv2.aruco.generateImageMarker(aruco_dict, marker_id, MARKER_SIZE_PX)
    return marker_image

def create_pdf_template(output_path):
    """ArUcoマーカー付きPDFテンプレートを生成"""
    # PDFキャンバスを作成
    c = canvas.Canvas(output_path, pagesize=A4)
    width, height = A4

    # タイトル
    c.setFont("Helvetica-Bold", 16)
    c.drawString(50, height - 30, "B_BLOCK Template (Optimized for OCR)")

    # 説明文
    c.setFont("Helvetica", 10)
    c.drawString(50, height - 50, "ArUco Markers: DICT_4X4_50, IDs 0-3")
    c.drawString(50, height - 65, "Marker Size: 25mm x 25mm")
    c.drawString(50, height - 80, "Marker Position: 15mm from edges (improved for closer camera)")

    # 各マーカーを配置
    for marker_info in MARKERS:
        marker_id = marker_info["id"]
        center_x_mm = marker_info["x"]
        center_y_mm = marker_info["y"]

        # マーカー画像を生成
        marker_img = generate_aruco_marker(marker_id)

        # NumPy配列をPIL Imageに変換
        from PIL import Image
        pil_img = Image.fromarray(marker_img)

        # BytesIOに保存
        img_buffer = BytesIO()
        pil_img.save(img_buffer, format='PNG')
        img_buffer.seek(0)

        # PDFの座標系に変換（左下が原点）
        # 中心座標からマーカーの左下座標を計算
        marker_left_mm = center_x_mm - (MARKER_SIZE_MM / 2)
        marker_bottom_mm = A4_HEIGHT_MM - center_y_mm - (MARKER_SIZE_MM / 2)

        # ReportLabの座標系に変換
        x = marker_left_mm * mm
        y = marker_bottom_mm * mm

        # マーカーを描画
        c.drawImage(
            ImageReader(img_buffer),
            x, y,
            width=MARKER_SIZE_MM * mm,
            height=MARKER_SIZE_MM * mm
        )

        # マーカーIDをラベル表示
        c.setFont("Helvetica", 8)
        label_x = center_x_mm * mm
        label_y = (A4_HEIGHT_MM - center_y_mm) * mm
        c.drawCentredString(label_x, label_y - 3, f"ID {marker_id}")

    # レシート配置領域を示す枠線（参考用）
    c.setStrokeColorRGB(0.8, 0.8, 0.8)
    c.setDash(2, 2)

    # ブロック領域の計算（マーカー間の領域）
    block_left_mm = MARKERS[0]["x"] + (MARKER_SIZE_MM / 2) + 1  # +1mm マージン
    block_right_mm = MARKERS[1]["x"] - (MARKER_SIZE_MM / 2) - 1
    block_top_mm = MARKERS[0]["y"] + (MARKER_SIZE_MM / 2) + 1
    block_bottom_mm = MARKERS[3]["y"] - (MARKER_SIZE_MM / 2) - 1

    # 実線の枠を描画
    c.setStrokeColorRGB(0.5, 0.5, 0.5)
    c.setDash(1, 0)
    c.rect(
        block_left_mm * mm,
        (A4_HEIGHT_MM - block_bottom_mm) * mm,
        (block_right_mm - block_left_mm) * mm,
        (block_bottom_mm - block_top_mm) * mm
    )

    # レシート配置説明
    c.setFont("Helvetica", 9)
    c.setFillColorRGB(0.5, 0.5, 0.5)
    instruction_y = (A4_HEIGHT_MM - block_top_mm - (block_bottom_mm - block_top_mm) / 2) * mm
    c.drawCentredString(
        (block_left_mm + (block_right_mm - block_left_mm) / 2) * mm,
        instruction_y,
        "Place receipt here"
    )

    # サイズ情報
    c.setFont("Helvetica", 8)
    c.drawString(
        block_left_mm * mm,
        (A4_HEIGHT_MM - block_bottom_mm - 8) * mm,
        f"Receipt area: {block_right_mm - block_left_mm:.1f}mm x {block_bottom_mm - block_top_mm:.1f}mm"
    )

    # PDFを保存
    c.save()
    print(f"PDF template created: {output_path}")
    print(f"Marker positions (from edge): 15mm")
    print(f"Receipt area size: {block_right_mm - block_left_mm:.1f}mm x {block_bottom_mm - block_top_mm:.1f}mm")

if __name__ == "__main__":
    output_file = "B_BLOCK_Template_15mm.pdf"
    create_pdf_template(output_file)
    print("\nUsage:")
    print("1. Print this PDF on A4 paper")
    print("2. Place receipt in the marked area")
    print("3. Scan with the app - camera can now get 1.5x closer!")
