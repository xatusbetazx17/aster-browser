"""Aster Browser - Modern Visual Windows Installer.

Built with CustomTkinter for a sleek, modern, native dark-mode experience.
Can be run directly or packaged into a standalone single-file .exe with PyInstaller.
"""
from __future__ import annotations

import os
import sys
import shutil
import zipfile
import threading
import subprocess
import time
import winreg
from pathlib import Path
from PIL import Image

try:
    import customtkinter as ctk
except ImportError:
    raise SystemExit("customtkinter is required. Install with: pip install customtkinter")

APP_NAME = "Aster Browser"
APP_VERSION = "16.0.0"
DEFAULT_INSTALL_DIR = os.path.join(os.environ.get("LOCALAPPDATA", os.path.expanduser("~")), "AsterBrowser")

def get_bundle_path() -> str:
    """Find bundled app_bundle.zip inside PyInstaller _MEIPASS or local dir."""
    if hasattr(sys, "_MEIPASS"):
        p = os.path.join(sys._MEIPASS, "app_bundle.zip")
        if os.path.exists(p):
            return p
    local_p = os.path.join(os.path.dirname(os.path.abspath(__file__)), "app_bundle.zip")
    if os.path.exists(local_p):
        return local_p
    parent_p = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "windows_installer", "app_bundle.zip")
    if os.path.exists(parent_p):
        return parent_p
    raise FileNotFoundError("app_bundle.zip was not found.")

def get_asset_path(filename: str) -> str | None:
    """Find asset file inside PyInstaller _MEIPASS or local dir."""
    if hasattr(sys, "_MEIPASS"):
        p = os.path.join(sys._MEIPASS, filename)
        if os.path.exists(p):
            return p
    local_p = os.path.join(os.path.dirname(os.path.abspath(__file__)), filename)
    if os.path.exists(local_p):
        return local_p
    return None

def create_windows_shortcut(target: str, shortcut_path: str, working_dir: str, icon_path: str = "", arguments: str = "") -> None:
    """Create a Windows .lnk shortcut via PowerShell COM WScript.Shell."""
    ps_cmd = (
        f'$WshShell = New-Object -ComObject WScript.Shell; '
        f'$Shortcut = $WshShell.CreateShortcut("{shortcut_path}"); '
        f'$Shortcut.TargetPath = "{target}"; '
        f'$Shortcut.WorkingDirectory = "{working_dir}"; '
    )
    if arguments:
        ps_cmd += f'$Shortcut.Arguments = "{arguments}"; '
    if icon_path and os.path.exists(icon_path):
        ps_cmd += f'$Shortcut.IconLocation = "{icon_path}"; '
    ps_cmd += '$Shortcut.Save()'
    
    subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", ps_cmd], capture_output=True)

class AsterInstallerApp(ctk.CTk):
    def __init__(self, uninstall_mode: bool = False):
        super().__init__()
        
        self.uninstall_mode = uninstall_mode
        self.title(f"{APP_NAME} - {'Kaldırma Sihirbazı' if uninstall_mode else 'Kurulum Sihirbazı'}")
        self.geometry("640x530")
        self.resizable(False, False)
        
        # Modern Dark Palette
        ctk.set_appearance_mode("dark")
        ctk.set_default_color_theme("blue")
        
        # Set window icon if available
        ico_path = get_asset_path("aster.ico")
        if ico_path and os.path.exists(ico_path):
            try:
                self.iconbitmap(ico_path)
            except Exception:
                pass
                
        self.install_dir = DEFAULT_INSTALL_DIR
        self.create_desktop_sc = True
        self.create_startmenu_sc = True
        self.launch_after = True
        
        self.container = ctk.CTkFrame(self, fg_color="#12151c", corner_radius=0)
        self.container.pack(fill="both", expand=True)
        
        if self.uninstall_mode:
            self.show_uninstall_page()
        else:
            self.show_config_page()

    def clear_container(self):
        for widget in self.container.winfo_children():
            widget.destroy()

    def _render_header(self, title: str, subtitle: str):
        header_frame = ctk.CTkFrame(self.container, fg_color="transparent")
        header_frame.pack(fill="x", padx=30, pady=(25, 15))
        
        # Logo
        png_path = get_asset_path("aster_logo.png")
        if png_path and os.path.exists(png_path):
            try:
                logo_img = ctk.CTkImage(light_image=Image.open(png_path), dark_image=Image.open(png_path), size=(56, 56))
                logo_lbl = ctk.CTkLabel(header_frame, text="", image=logo_img)
                logo_lbl.pack(side="left", padx=(0, 15))
            except Exception:
                pass
                
        title_box = ctk.CTkFrame(header_frame, fg_color="transparent")
        title_box.pack(side="left", fill="y", expand=True, anchor="w")
        
        t_label = ctk.CTkLabel(
            title_box,
            text=title,
            font=ctk.CTkFont(family="Segoe UI", size=22, weight="bold"),
            text_color="#f8fafc"
        )
        t_label.pack(anchor="w")
        
        sub_label = ctk.CTkLabel(
            title_box,
            text=subtitle,
            font=ctk.CTkFont(family="Segoe UI", size=12),
            text_color="#94a3b8"
        )
        sub_label.pack(anchor="w", pady=(2, 0))

    def show_config_page(self):
        self.clear_container()
        self._render_header(
            title="Aster Browser Kurulumu",
            subtitle=f"Sürüm: v{APP_VERSION}  |  Hafif, Bağımsız ve Gizlilik Odaklı Web Tarayıcısı"
        )
        
        # Main Card Frame
        card = ctk.CTkFrame(self.container, fg_color="#1c212c", corner_radius=12, border_width=1, border_color="#2a3242")
        card.pack(fill="both", expand=True, padx=30, pady=(5, 20))
        
        # 1. Directory selection section
        dir_title = ctk.CTkLabel(card, text="Kurulum Konumu:", font=ctk.CTkFont(family="Segoe UI", size=13, weight="bold"), text_color="#e2e8f0")
        dir_title.pack(anchor="w", padx=20, pady=(15, 6))
        
        dir_box = ctk.CTkFrame(card, fg_color="transparent")
        dir_box.pack(fill="x", padx=20, pady=(0, 12))
        
        self.path_entry = ctk.CTkEntry(
            dir_box,
            font=ctk.CTkFont(family="Segoe UI", size=12),
            height=36,
            fg_color="#141720",
            border_color="#333d52",
            text_color="#f1f5f9"
        )
        self.path_entry.insert(0, self.install_dir)
        self.path_entry.pack(side="left", fill="x", expand=True, padx=(0, 10))
        
        browse_btn = ctk.CTkButton(
            dir_box,
            text="Gözat...",
            width=85,
            height=36,
            font=ctk.CTkFont(family="Segoe UI", size=12),
            fg_color="#2d3748",
            hover_color="#3f4e66",
            text_color="#f8fafc",
            command=self._on_browse
        )
        browse_btn.pack(side="right")
        
        # Space requirement label
        space_lbl = ctk.CTkLabel(card, text="Gereken disk alanı: ~25 MB", font=ctk.CTkFont(family="Segoe UI", size=11), text_color="#64748b")
        space_lbl.pack(anchor="w", padx=20, pady=(0, 15))
        
        # Divider
        divider = ctk.CTkFrame(card, height=1, fg_color="#2a3242")
        divider.pack(fill="x", padx=20, pady=(0, 15))
        
        # 2. Options Checkboxes
        opt_title = ctk.CTkLabel(card, text="Kurulum Seçenekleri:", font=ctk.CTkFont(family="Segoe UI", size=13, weight="bold"), text_color="#e2e8f0")
        opt_title.pack(anchor="w", padx=20, pady=(0, 8))
        
        self.chk_desktop = ctk.CTkCheckBox(
            card,
            text="Masaüstüne kısayol simgesi ekle",
            font=ctk.CTkFont(family="Segoe UI", size=12),
            text_color="#cbd5e1",
            fg_color="#0284c7",
            hover_color="#0369a1"
        )
        self.chk_desktop.select()
        self.chk_desktop.pack(anchor="w", padx=25, pady=(2, 6))
        
        self.chk_startmenu = ctk.CTkCheckBox(
            card,
            text="Başlat menüsü programlarına ekle",
            font=ctk.CTkFont(family="Segoe UI", size=12),
            text_color="#cbd5e1",
            fg_color="#0284c7",
            hover_color="#0369a1"
        )
        self.chk_startmenu.select()
        self.chk_startmenu.pack(anchor="w", padx=25, pady=(2, 6))
        
        self.chk_launch = ctk.CTkCheckBox(
            card,
            text="Kurulum tamamlandığında Aster Browser'ı başlat",
            font=ctk.CTkFont(family="Segoe UI", size=12),
            text_color="#cbd5e1",
            fg_color="#0284c7",
            hover_color="#0369a1"
        )
        self.chk_launch.select()
        self.chk_launch.pack(anchor="w", padx=25, pady=(2, 10))
        
        # Footer Action Buttons
        footer = ctk.CTkFrame(self.container, fg_color="transparent")
        footer.pack(fill="x", padx=30, pady=(0, 20))
        
        cancel_btn = ctk.CTkButton(
            footer,
            text="İptal",
            width=95,
            height=40,
            font=ctk.CTkFont(family="Segoe UI", size=13),
            fg_color="#2d3748",
            hover_color="#374151",
            text_color="#94a3b8",
            command=self.destroy
        )
        cancel_btn.pack(side="left")
        
        install_btn = ctk.CTkButton(
            footer,
            text="Kurulumu Başlat",
            width=160,
            height=40,
            font=ctk.CTkFont(family="Segoe UI", size=13, weight="bold"),
            fg_color="#0284c7",
            hover_color="#0369a1",
            text_color="#ffffff",
            command=self._start_installation
        )
        install_btn.pack(side="right")

    def _on_browse(self):
        from tkinter import filedialog
        chosen = filedialog.askdirectory(initialdir=self.path_entry.get(), title="Aster Browser Kurulum Klasörünü Seçin")
        if chosen:
            self.path_entry.delete(0, "end")
            self.path_entry.insert(0, os.path.normpath(chosen))

    def _start_installation(self):
        self.install_dir = os.path.normpath(self.path_entry.get().strip())
        self.create_desktop_sc = bool(self.chk_desktop.get())
        self.create_startmenu_sc = bool(self.chk_startmenu.get())
        self.launch_after = bool(self.chk_launch.get())
        
        self.show_progress_page()
        threading.Thread(target=self._installation_worker, daemon=True).start()

    def show_progress_page(self):
        self.clear_container()
        self._render_header(
            title="Aster Browser Kuruluyor",
            subtitle="Lütfen kurulum adımları tamamlanırken bekleyin..."
        )
        
        card = ctk.CTkFrame(self.container, fg_color="#1c212c", corner_radius=12, border_width=1, border_color="#2a3242")
        card.pack(fill="both", expand=True, padx=30, pady=(5, 20))
        
        self.status_lbl = ctk.CTkLabel(
            card,
            text="Kurulum hazırlanıyor...",
            font=ctk.CTkFont(family="Segoe UI", size=13, weight="bold"),
            text_color="#38bdf8"
        )
        self.status_lbl.pack(anchor="w", padx=20, pady=(20, 8))
        
        self.progress_bar = ctk.CTkProgressBar(
            card,
            height=12,
            corner_radius=6,
            fg_color="#141720",
            progress_color="#0284c7"
        )
        self.progress_bar.pack(fill="x", padx=20, pady=(0, 15))
        self.progress_bar.set(0.05)
        
        # Log Box
        self.log_box = ctk.CTkTextbox(
            card,
            font=ctk.CTkFont(family="Consolas", size=10),
            fg_color="#141720",
            text_color="#94a3b8",
            corner_radius=8,
            border_width=1,
            border_color="#283142"
        )
        self.log_box.pack(fill="both", expand=True, padx=20, pady=(0, 20))

    def _log(self, text: str):
        self.after(0, self._append_log, text)

    def _append_log(self, text: str):
        self.log_box.insert("end", text + "\n")
        self.log_box.see("end")

    def _set_status(self, text: str, progress: float):
        self.after(0, self._update_status, text, progress)

    def _update_status(self, text: str, progress: float):
        self.status_lbl.configure(text=text)
        self.progress_bar.set(progress)

    def _installation_worker(self):
        try:
            root = self.install_dir
            app_dir = os.path.join(root, "app")
            state_dir = os.path.join(root, "state")
            runtime_dir = os.path.join(root, "runtime")
            
            # Step 1: Prepare directories
            self._set_status("Hedef dizin hazırlanıyor...", 0.15)
            self._log(f"[1/5] Hedef dizin oluşturuluyor: {root}")
            os.makedirs(root, exist_ok=True)
            os.makedirs(state_dir, exist_ok=True)
            
            if os.path.exists(app_dir):
                self._log("Eski uygulama dosyaları temizleniyor...")
                shutil.rmtree(app_dir, ignore_errors=True)
            os.makedirs(app_dir, exist_ok=True)
            
            # Step 2: Extract bundled app
            self._set_status("Uygulama dosyaları arşivden çıkartılıyor...", 0.35)
            bundle_zip = get_bundle_path()
            self._log(f"[2/5] Paket çıkartılıyor: {os.path.basename(bundle_zip)}")
            
            with zipfile.ZipFile(bundle_zip, "r") as z:
                total_files = len(z.infolist())
                for i, item in enumerate(z.infolist()):
                    z.extract(item, app_dir)
                    if i % 25 == 0 or i == total_files - 1:
                        p = 0.35 + (0.35 * (i / max(1, total_files)))
                        self._set_status(f"Dosyalar çıkartılıyor ({i+1}/{total_files})...", p)
            
            self._log(f"Toplam {total_files} dosya başarıyla açıldı.")
            
            # Step 3: Copy icons and generate batch launcher
            self._set_status("Başlatıcı ve sistem konfigürasyonu yapılıyor...", 0.75)
            self._log("[3/5] Başlatıcı ve ortam ayarlanıyor...")
            
            # Ensure aster.ico is in root as well
            ico_src = get_asset_path("aster.ico")
            ico_dest = os.path.join(root, "aster.ico")
            if ico_src and os.path.exists(ico_src):
                shutil.copy2(ico_src, ico_dest)
            
            # Create AsterBrowser.bat launcher
            launcher_bat = os.path.join(root, "AsterBrowser.bat")
            bat_content = (
                "@echo off\r\n"
                "set \"ASTER_PORTABLE=1\"\r\n"
                "set \"ASTER_PORTABLE_ROOT=%~dp0state\"\r\n"
                "set \"QTWEBENGINE_CHROMIUM_FLAGS=--disable-gpu-sandbox --enable-features=VaapiVideoDecoder\"\r\n"
                "cd /d \"%~dp0app\"\r\n"
                "if exist \"%~dp0runtime\\Scripts\\pythonw.exe\" (\r\n"
                "    start \"\" \"%~dp0runtime\\Scripts\\pythonw.exe\" \"%~dp0app\\run_aster.py\" %*\r\n"
                ") else if exist \"%~dp0runtime\\Scripts\\python.exe\" (\r\n"
                "    \"%~dp0runtime\\Scripts\\python.exe\" \"%~dp0app\\run_aster.py\" %*\r\n"
                ") else (\r\n"
                "    py -3 \"%~dp0app\\run_aster.py\" %* 2>nul || python \"%~dp0app\\run_aster.py\" %*\r\n"
                ")\r\n"
            )
            with open(launcher_bat, "w", encoding="ascii") as f:
                f.write(bat_content)
            self._log(f"Başlatıcı oluşturuldu: {launcher_bat}")
            
            # Create Uninstall.bat
            uninst_bat = os.path.join(root, "Uninstall.bat")
            desktop_path = os.path.join(os.path.expanduser("~"), "Desktop", f"{APP_NAME}.lnk")
            start_menu_dir = os.path.join(os.environ.get("APPDATA", ""), "Microsoft", "Windows", "Start Menu", "Programs", APP_NAME)
            uninst_content = (
                "@echo off\r\n"
                "echo Aster Browser kaldiriliyor...\r\n"
                f"del /f /q \"{desktop_path}\" 2>nul\r\n"
                f"rmdir /s /q \"{start_menu_dir}\" 2>nul\r\n"
                "reg delete \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\AsterBrowser\" /f 2>nul\r\n"
                "cd /d \"%LOCALAPPDATA%\"\r\n"
                f"echo Siliniyor: {root}\r\n"
                f"rmdir /s /q \"{root}\" 2>nul\r\n"
                "echo Aster Browser basariyla kaldirildi.\r\n"
                "pause\r\n"
            )
            with open(uninst_bat, "w", encoding="ascii") as f:
                f.write(uninst_content)
            
            # Step 4: Create Shortcuts
            self._set_status("Kısayollar oluşturuluyor...", 0.88)
            self._log("[4/5] Windows kısayolları oluşturuluyor...")
            
            pythonw_target = os.path.join(runtime_dir, "Scripts", "pythonw.exe")
            run_py = os.path.join(app_dir, "run_aster.py")
            target_exe = pythonw_target if os.path.exists(pythonw_target) else launcher_bat
            args = f'"{run_py}"' if target_exe == pythonw_target else ""

            if self.create_desktop_sc:
                create_windows_shortcut(
                    target=target_exe,
                    shortcut_path=desktop_path,
                    working_dir=app_dir,
                    icon_path=ico_dest,
                    arguments=args
                )
                self._log(f"Masaüstü kısayolu oluşturuldu: {desktop_path}")
                
            if self.create_startmenu_sc:
                os.makedirs(start_menu_dir, exist_ok=True)
                sm_shortcut = os.path.join(start_menu_dir, f"{APP_NAME}.lnk")
                create_windows_shortcut(
                    target=target_exe,
                    shortcut_path=sm_shortcut,
                    working_dir=app_dir,
                    icon_path=ico_dest,
                    arguments=args
                )
                self._log(f"Başlat menüsü kısayolu oluşturuldu: {sm_shortcut}")
            
            # Step 5: Windows Registry registration
            self._set_status("Sistem kayıtları yapılıyor...", 0.95)
            self._log("[5/5] Windows Denetim Masası Program Ekle/Kaldır kaydı yapılıyor...")
            try:
                reg_path = r"Software\Microsoft\Windows\CurrentVersion\Uninstall\AsterBrowser"
                with winreg.CreateKey(winreg.HKEY_CURRENT_USER, reg_path) as key:
                    winreg.SetValueEx(key, "DisplayName", 0, winreg.REG_SZ, APP_NAME)
                    winreg.SetValueEx(key, "DisplayVersion", 0, winreg.REG_SZ, APP_VERSION)
                    winreg.SetValueEx(key, "Publisher", 0, winreg.REG_SZ, "Aster Project")
                    winreg.SetValueEx(key, "InstallLocation", 0, winreg.REG_SZ, root)
                    winreg.SetValueEx(key, "DisplayIcon", 0, winreg.REG_SZ, ico_dest)
                    winreg.SetValueEx(key, "UninstallString", 0, winreg.REG_SZ, f'"{uninst_bat}"')
            except Exception as reg_err:
                self._log(f"Kayıt defteri uyarısı (atlandı): {reg_err}")

            time.sleep(0.4)
            self._set_status("Kurulum başarıyla tamamlandı!", 1.0)
            self._log("Tamamlandı!")
            self.after(500, self.show_completed_page)
            
        except Exception as err:
            self._log(f"HATA: {err}")
            self._set_status(f"Kurulum başarısız: {err}", 1.0)

    def show_completed_page(self):
        self.clear_container()
        self._render_header(
            title="Kurulum Başarıyla Tamamlandı!",
            subtitle=f"{APP_NAME} bilgisayarınıza başarıyla kuruldu."
        )
        
        card = ctk.CTkFrame(self.container, fg_color="#1c212c", corner_radius=12, border_width=1, border_color="#2a3242")
        card.pack(fill="both", expand=True, padx=30, pady=(5, 20))
        
        check_lbl = ctk.CTkLabel(
            card,
            text="✓",
            font=ctk.CTkFont(family="Segoe UI", size=64, weight="bold"),
            text_color="#4ade80"
        )
        check_lbl.pack(pady=(20, 5))
        
        desc_lbl = ctk.CTkLabel(
            card,
            text="Aster Browser kullanıma hazır!",
            font=ctk.CTkFont(family="Segoe UI", size=16, weight="bold"),
            text_color="#f8fafc"
        )
        desc_lbl.pack(pady=(0, 8))
        
        loc_lbl = ctk.CTkLabel(
            card,
            text=f"Kurulum Konumu:\n{self.install_dir}",
            font=ctk.CTkFont(family="Segoe UI", size=12),
            text_color="#94a3b8"
        )
        loc_lbl.pack(pady=(0, 15))
        
        # Footer Action Buttons
        footer = ctk.CTkFrame(self.container, fg_color="transparent")
        footer.pack(fill="x", padx=30, pady=(0, 20))
        
        close_btn = ctk.CTkButton(
            footer,
            text="Tamamla",
            width=110,
            height=40,
            font=ctk.CTkFont(family="Segoe UI", size=13),
            fg_color="#2d3748",
            hover_color="#374151",
            text_color="#cbd5e1",
            command=self.destroy
        )
        close_btn.pack(side="left")
        
        launch_btn = ctk.CTkButton(
            footer,
            text="Aster Browser'ı Başlat",
            width=180,
            height=40,
            font=ctk.CTkFont(family="Segoe UI", size=13, weight="bold"),
            fg_color="#0284c7",
            hover_color="#0369a1",
            text_color="#ffffff",
            command=self._launch_app_and_exit
        )
        launch_btn.pack(side="right")
        
        if self.launch_after:
            # Trigger immediate launch
            self._launch_app()

    def _launch_app(self):
        launcher_bat = os.path.join(self.install_dir, "AsterBrowser.bat")
        if os.path.exists(launcher_bat):
            try:
                subprocess.Popen(["cmd.exe", "/c", launcher_bat], cwd=os.path.join(self.install_dir, "app"), creationflags=subprocess.CREATE_NEW_CONSOLE)
            except Exception as e:
                print("Launch error:", e)

    def _launch_app_and_exit(self):
        self._launch_app()
        self.destroy()

    def show_uninstall_page(self):
        self.clear_container()
        self._render_header(
            title="Aster Browser Kaldırma",
            subtitle=f"{APP_NAME} uygulamasını sistemden kaldırmak istediğinizden emin misiniz?"
        )
        
        card = ctk.CTkFrame(self.container, fg_color="#1c212c", corner_radius=12, border_width=1, border_color="#2a3242")
        card.pack(fill="both", expand=True, padx=30, pady=(5, 20))
        
        warn_lbl = ctk.CTkLabel(
            card,
            text="Dikkat",
            font=ctk.CTkFont(family="Segoe UI", size=20, weight="bold"),
            text_color="#f87171"
        )
        warn_lbl.pack(pady=(25, 10))
        
        desc_lbl = ctk.CTkLabel(
            card,
            text=f"Kurulu konum:\n{self.install_dir}\n\nTüm uygulama dosyaları ve oluşturulan kısayollar silinecektir.",
            font=ctk.CTkFont(family="Segoe UI", size=13),
            text_color="#94a3b8"
        )
        desc_lbl.pack(pady=(0, 20))
        
        footer = ctk.CTkFrame(self.container, fg_color="transparent")
        footer.pack(fill="x", padx=30, pady=(0, 20))
        
        cancel_btn = ctk.CTkButton(
            footer,
            text="Vazgeç",
            width=100,
            height=40,
            font=ctk.CTkFont(family="Segoe UI", size=13),
            fg_color="#2d3748",
            hover_color="#374151",
            text_color="#cbd5e1",
            command=self.destroy
        )
        cancel_btn.pack(side="left")
        
        uninst_btn = ctk.CTkButton(
            footer,
            text="Uygulamayı Kaldır",
            width=160,
            height=40,
            font=ctk.CTkFont(family="Segoe UI", size=13, weight="bold"),
            fg_color="#dc2626",
            hover_color="#b91c1c",
            text_color="#ffffff",
            command=self._do_uninstall
        )
        uninst_btn.pack(side="right")

    def _do_uninstall(self):
        uninst_bat = os.path.join(self.install_dir, "Uninstall.bat")
        if os.path.exists(uninst_bat):
            subprocess.Popen(["cmd.exe", "/c", uninst_bat])
        self.destroy()

def main():
    uninstall = "--uninstall" in sys.argv
    app = AsterInstallerApp(uninstall_mode=uninstall)
    app.mainloop()

if __name__ == "__main__":
    main()
