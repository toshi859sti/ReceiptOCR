#!/usr/bin/env python3
"""
Arucoマーカー生成スクリプト
ID 0-7のマーカーを生成してPDFとして保存
"""

import cv2
import numpy as np
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas
from reportlab.lib.utils import ImageReader
from io import BytesIO
from PIL import Image

# 設定
MARKER_SIZE = 200  # マーカーサイズ（ピクセル）
BORDER_BITS = 1
MARKER_IDS = list(range(8))  # ID 0-7

# 利用可能な辞書
DICTIONARIES = {
    'DICT_4X4_50': cv2.aruco.DICT_4X4_50,
    'DICT_4X4_100': cv2.aruco.DICT_4X4_100,
    'DICT_4X4_250': cv2.aruco.DICT_4X4_250,
    'DICT_4X4_1000': cv2.aruco.DICT_4X4_1000,
    'DICT_5X5_50': cv2.aruco.DICT_5X5_50,
    'DICT_6X6_50': cv2.aruco.DICT_6X6_50,
}

def generate_marker(dictionary, marker_id, marker_size=200):
    """
    Arucoマーカーを生成
    """
    aruco_dict = cv2.aruco.getPredefinedDictionary(dictionary)
    marker_image = cv2.aruco.generateImageMarker(aruco_dict, marker_id, marker_size)
    return marker_image

def create_pdf_with_markers(dict_name, dict_type, output_filename):
    """
    マーカーをPDFに配置
    BブロックとCブロック用に2ページ作成
    """
    c = canvas.Canvas(output_filename, pagesize=A4)
    page_width, page_height = A4

    # マーカーサイズ（mm -> points, 1mm = 2.834645669 points）
    marker_size_mm = 30  # 30mm x 30mm
    marker_size_pt = marker_size_mm * 2.834645669

    # Bブロック用マーカー (ID: 0, 1, 2, 3)
    c.setFont("Helvetica-Bold", 16)
    c.drawString(50, page_height - 50, f"ArUco Markers - B Block ({dict_name})")
    c.setFont("Helvetica", 10)
    c.drawString(50, page_height - 70, "IDs: 0 (Top-Left), 1 (Top-Right), 2 (Bottom-Left), 3 (Bottom-Right)")

    # 台紙の枠を描画（A5サイズ相当：148mm x 210mm）
    frame_width_mm = 210
    frame_height_mm = 148
    frame_width_pt = frame_width_mm * 2.834645669
    frame_height_pt = frame_height_mm * 2.834645669

    frame_x = (page_width - frame_width_pt) / 2
    frame_y = (page_height - frame_height_pt) / 2

    c.rect(frame_x, frame_y, frame_width_pt, frame_height_pt, stroke=1, fill=0)

    # マーカーの配置位置（四隅）
    margin = 10 * 2.834645669  # 10mm
    positions = [
        (frame_x + margin, frame_y + frame_height_pt - margin - marker_size_pt, 0, "Top-Left"),     # 左上
        (frame_x + frame_width_pt - margin - marker_size_pt, frame_y + frame_height_pt - margin - marker_size_pt, 1, "Top-Right"),  # 右上
        (frame_x + margin, frame_y + margin, 2, "Bottom-Left"),  # 左下
        (frame_x + frame_width_pt - margin - marker_size_pt, frame_y + margin, 3, "Bottom-Right"),  # 右下
    ]

    for x, y, marker_id, label in positions:
        # マーカー生成
        marker_img = generate_marker(dict_type, marker_id, MARKER_SIZE)

        # NumPy配列をPIL Imageに変換
        pil_img = Image.fromarray(marker_img)

        # BytesIOに保存
        img_buffer = BytesIO()
        pil_img.save(img_buffer, format='PNG')
        img_buffer.seek(0)

        # PDFに描画
        c.drawImage(ImageReader(img_buffer), x, y, width=marker_size_pt, height=marker_size_pt)

        # ラベル追加
        c.setFont("Helvetica", 8)
        c.drawString(x, y - 15, f"ID: {marker_id} ({label})")

    c.showPage()

    # Cブロック用マーカー (ID: 4, 5, 6, 7)
    c.setFont("Helvetica-Bold", 16)
    c.drawString(50, page_height - 50, f"ArUco Markers - C Block ({dict_name})")
    c.setFont("Helvetica", 10)
    c.drawString(50, page_height - 70, "IDs: 4 (Top-Left), 5 (Top-Right), 6 (Bottom-Left), 7 (Bottom-Right)")

    c.rect(frame_x, frame_y, frame_width_pt, frame_height_pt, stroke=1, fill=0)

    positions = [
        (frame_x + margin, frame_y + frame_height_pt - margin - marker_size_pt, 4, "Top-Left"),
        (frame_x + frame_width_pt - margin - marker_size_pt, frame_y + frame_height_pt - margin - marker_size_pt, 5, "Top-Right"),
        (frame_x + margin, frame_y + margin, 6, "Bottom-Left"),
        (frame_x + frame_width_pt - margin - marker_size_pt, frame_y + margin, 7, "Bottom-Right"),
    ]

    for x, y, marker_id, label in positions:
        marker_img = generate_marker(dict_type, marker_id, MARKER_SIZE)
        pil_img = Image.fromarray(marker_img)
        img_buffer = BytesIO()
        pil_img.save(img_buffer, format='PNG')
        img_buffer.seek(0)
        c.drawImage(ImageReader(img_buffer), x, y, width=marker_size_pt, height=marker_size_pt)
        c.setFont("Helvetica", 8)
        c.drawString(x, y - 15, f"ID: {marker_id} ({label})")

    c.save()
    print(f"PDF saved: {output_filename}")

if __name__ == "__main__":
    # 各辞書でマーカーPDFを生成
    for dict_name, dict_type in DICTIONARIES.items():
        output_file = f"aruco_markers_{dict_name}.pdf"
        create_pdf_with_markers(dict_name, dict_type, output_file)
        print(f"Generated: {output_file}")

    print("\n生成完了！")
    print("各PDFファイルを印刷またはPCで表示してテストしてください。")
    print("推奨: DICT_4X4_50から試してください。")
