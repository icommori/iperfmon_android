import os
import sys
import subprocess
from PIL import Image

# --- 配置區 ---
TARGET_WIDTH = 640
REQUIRED_LIBS = ["Pillow"]
VENV_DIR = ".venv"
# 擴展支援的副檔名
VALID_EXTENSIONS = ('.jpg', '.jpeg', '.JPG', '.JPEG', '.png', '.PNG')

def setup_venv():
    """檢查並設定虛擬環境與所需套件"""
    if not (sys.prefix != sys.base_prefix or 'VIRTUAL_ENV' in os.environ):
        if not os.path.exists(VENV_DIR):
            print(f"[*] 建立虛擬環境 {VENV_DIR}...")
            subprocess.check_call([sys.executable, "-m", "venv", VENV_DIR])
        
        venv_python = os.path.join(VENV_DIR, "bin", "python")
        print("[*] 安裝必要套件 (Pillow)...")
        subprocess.check_call([venv_python, "-m", "pip", "install", "--upgrade", "pip"])
        subprocess.check_call([venv_python, "-m", "pip", "install"] + REQUIRED_LIBS)
        
        print("-" * 40)
        print(f"[!] 環境準備就緒，請執行：")
        print(f"    source {VENV_DIR}/bin/activate && python {sys.argv[0]}")
        print("-" * 40)
        sys.exit(0)

def resize_images(directory):
    """核心轉換邏輯"""
    count = 0
    for filename in os.listdir(directory):
        if filename.endswith(VALID_EXTENSIONS):
            file_path = os.path.join(directory, filename)
            try:
                with Image.open(file_path) as img:
                    orig_width, orig_height = img.size
                    
                    # 計算比例
                    ratio = TARGET_WIDTH / float(orig_width)
                    target_height = int(float(orig_height) * float(ratio))
                    
                    # 執行縮放
                    resized_img = img.resize((TARGET_WIDTH, target_height), Image.Resampling.LANCZOS)
                    
                    # 處理 PNG 的透明度問題 (若要覆蓋原檔且保持 PNG 格式)
                    # 如果原圖是 RGBA，我們保留它；如果要轉 JPG 才需要轉 RGB
                    # 這裡採直接覆蓋，所以維持原模式
                    resized_img.save(file_path, quality=95)
                    
                count += 1
                print(f"[OK] {filename} -> {TARGET_WIDTH}x{target_height}")
            except Exception as e:
                print(f"[ERROR] 處理 {filename} 失敗: {e}")
    return count

if __name__ == "__main__":
    setup_venv()

    target_dir = os.getcwd()
    print(f"[*] 工作目錄: {target_dir}")
    print(f"[*] 支援格式: {', '.join(VALID_EXTENSIONS)}")
    
    confirm = input(f"[危險] 將覆蓋所有 JPG/PNG (寬度縮至 {TARGET_WIDTH})，確定嗎？ (y/n): ")
    if confirm.lower() == 'y':
        total = resize_images(target_dir)
        print(f"\n[完成] 處理了 {total} 張圖片。")
    else:
        print("[取消] 操作終止。")
