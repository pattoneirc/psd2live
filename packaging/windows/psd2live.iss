; PSD2Live's Windows installer, built by build.gradle.kts (packageExe) from the app image with Inno Setup 6:
;   ISCC /DAppVersion=x.y.z /DAppImage=<app image> /DOutputDir=<folder> /DIconFile=<.ico> [/DArch=arm64] psd2live.iss
; Files are copied in place, so an upgrade needs no rollback copies, and the installer goes back to the folder of
; the installed version. Versions up to 3.1.x were MSI packages (jpackage with WiX); setup finds them by their
; upgrade code, removes them and installs into their folder.

#ifndef AppVersion
  #error Define AppVersion
#endif
#ifndef AppImage
  #error Define AppImage
#endif
#ifndef OutputDir
  #define OutputDir "."
#endif
; The app image's architecture: x64 (default) or arm64.
#ifndef Arch
  #define Arch "x64"
#endif

[Setup]
; Never change: Inno Setup finds the installed version by it, and the ffmpeg build shares it so either replaces the other.
AppId={{D4FE9F65-F78D-46BB-8927-24BE9617E4AC}
AppName=PSD2Live
AppVersion={#AppVersion}
AppVerName=PSD2Live {#AppVersion}
AppPublisher=PSD2Live
AppPublisherURL=https://github.com/tsunehimatoi/psd2live
AppSupportURL=https://github.com/tsunehimatoi/psd2live/issues
AppUpdatesURL=https://github.com/tsunehimatoi/psd2live/releases
AppCopyright=© 2026 PSD2Live. Licensed under GPL-3.0.
VersionInfoVersion={#AppVersion}
VersionInfoDescription=PSD2Live Setup
; All users by default, as the MSI packages installed; the dialog or /CURRENTUSER installs for the current user only.
PrivilegesRequired=admin
PrivilegesRequiredOverridesAllowed=dialog commandline
#if Arch == "arm64"
ArchitecturesAllowed=arm64
ArchitecturesInstallIn64BitMode=arm64
#else
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
#endif
DefaultDirName={code:DefaultDir}
; The folder page shows on a first install only; an upgrade goes back to the installed folder.
DisableDirPage=auto
DirExistsWarning=no
DefaultGroupName=PSD2Live
DisableProgramGroupPage=yes
UninstallDisplayName=PSD2Live
UninstallDisplayIcon={app}\PSD2Live.exe
#ifdef IconFile
SetupIconFile={#IconFile}
#endif
OutputDir={#OutputDir}
#if Arch == "arm64"
OutputBaseFilename=PSD2Live-{#AppVersion}-arm64
#else
OutputBaseFilename=PSD2Live-{#AppVersion}
#endif
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
ShowLanguageDialog=auto
; PrepareToInstall waits for PSD2Live to close; Restart Manager catches anything else holding the files.
CloseApplications=yes
RestartApplications=no
; A log in %TEMP% (Setup Log *.txt) for every install and uninstall, for reports of failed ones.
SetupLogging=yes
UninstallLogging=yes

[Languages]
Name: "en"; MessagesFile: "compiler:Default.isl"
; Not among the translations Inno Setup ships: the user-contributed one (jrsoftware.org/files/istrans), kept here.
Name: "zh"; MessagesFile: "ChineseSimplified.isl"
Name: "ja"; MessagesFile: "compiler:Languages\Japanese.isl"
Name: "ko"; MessagesFile: "compiler:Languages\Korean.isl"

[CustomMessages]
en.AppRunning=PSD2Live is running from the installation folder. Save your work and close it, then click Retry.
zh.AppRunning=PSD2Live 正在从安装目录运行。请保存工作并关闭它，然后点「重试」。
ja.AppRunning=PSD2Live がインストール先から実行中です。作業を保存して終了してから「再試行」を押してください。
ko.AppRunning=PSD2Live가 설치 폴더에서 실행 중입니다. 작업을 저장하고 종료한 뒤 [다시 시도]를 누르세요.
en.AppStillRunning=PSD2Live is still running.
zh.AppStillRunning=PSD2Live 仍在运行。
ja.AppStillRunning=PSD2Live がまだ実行中です。
ko.AppStillRunning=PSD2Live가 아직 실행 중입니다.
en.RemovingLegacy=Removing the installed PSD2Live %1...
zh.RemovingLegacy=正在移除已安装的 PSD2Live %1……
ja.RemovingLegacy=インストール済みの PSD2Live %1 を削除しています...
ko.RemovingLegacy=설치된 PSD2Live %1을(를) 제거하는 중...
en.LegacyRemoveFailed=The installed PSD2Live %1 could not be removed (error %2). Remove it in Settings > Apps, then run this setup again.
zh.LegacyRemoveFailed=无法移除已安装的 PSD2Live %1（错误 %2）。请在「设置 > 应用」中卸载它，然后重新运行安装程序。
ja.LegacyRemoveFailed=インストール済みの PSD2Live %1 を削除できませんでした（エラー %2）。「設定 > アプリ」からアンインストールしてから、もう一度セットアップを実行してください。
ko.LegacyRemoveFailed=설치된 PSD2Live %1을(를) 제거하지 못했습니다(오류 %2). [설정 > 앱]에서 제거한 뒤 설치 프로그램을 다시 실행하세요.
en.ClearDataPrompt=Also delete your PSD2Live data?%n%nSettings, the workspace store (working copies and edit history of opened projects) and downloaded runtimes and models of this Windows user. Saved projects (.psd2live) and files you put in the installation folder are kept.
zh.ClearDataPrompt=同时删除 PSD2Live 的数据吗？%n%n包括当前 Windows 用户的设置、工作区存储（已打开工程的工作副本与编辑历史）以及下载的运行时和模型。已保存的工程（.psd2live）和你放在安装目录里的文件会保留。
ja.ClearDataPrompt=PSD2Live のデータも削除しますか？%n%nこの Windows ユーザーの設定、ワークスペース（開いたプロジェクトの作業コピーと編集履歴）、ダウンロードしたランタイムとモデルが対象です。保存したプロジェクト（.psd2live）とインストール先に置いたファイルは残ります。
ko.ClearDataPrompt=PSD2Live 데이터도 삭제할까요?%n%n이 Windows 사용자의 설정, 작업 공간 저장소(연 프로젝트의 작업 사본과 편집 기록), 다운로드한 런타임과 모델이 삭제됩니다. 저장한 프로젝트(.psd2live)와 설치 폴더에 넣은 파일은 유지됩니다.

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"; Flags: unchecked

[InstallDelete]
; What the previous version installed, so files it had and this one has not do not linger. Files the user put in the
; installation folder itself stay.
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"

[Files]
Source: "{#AppImage}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\PSD2Live"; Filename: "{app}\PSD2Live.exe"
Name: "{autodesktop}\PSD2Live"; Filename: "{app}\PSD2Live.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\PSD2Live.exe"; Description: "{cm:LaunchProgram,PSD2Live}"; Flags: nowait postinstall skipifsilent runasoriginaluser

[Code]
const
  // The MSI packages of 3.1.x and earlier (jpackage --win-upgrade-uuid).
  LegacyUpgradeCode = '{8E9C4B1A-2D3E-4F5A-6B7C-8D9E0F1A2B3C}';
  // Where the MSI packages recorded their folder; jpackage's RemoveFolderEx in 3.0.0 and earlier read the folder it
  // empties on uninstall from the version's subkey.
  LegacyRecordKey = 'Software\PSD2Live\PSD2Live';
  ERROR_SUCCESS = 0;
  ERROR_NO_MORE_ITEMS = 259;
  ERROR_UNKNOWN_PRODUCT = 1605;
  ERROR_SUCCESS_REBOOT_INITIATED = 1641;
  ERROR_SUCCESS_REBOOT_REQUIRED = 3010;

type
  TLegacyProduct = record
    Code: String;
    Version: String;
    Folder: String;
    PerMachine: Boolean;
  end;

var
  LegacyProducts: array of TLegacyProduct;
  ClearUserData: Boolean;

function MsiEnumRelatedProducts(UpgradeCode: String; Reserved, Index: DWORD; ProductBuf: String): Cardinal;
  external 'MsiEnumRelatedProductsW@msi.dll stdcall';
function MsiGetProductInfo(Product, Name: String; ValueBuf: String; var ValueLength: DWORD): Cardinal;
  external 'MsiGetProductInfoW@msi.dll stdcall';

function CutAtNull(const S: String): String;
var
  I: Integer;
begin
  I := Pos(#0, S);
  if I > 0 then Result := Copy(S, 1, I - 1) else Result := S;
end;

function ProductInfo(const Code, Name: String): String;
var
  Buf: String;
  Len: DWORD;
begin
  Len := 1024;
  Buf := StringOfChar(#0, Len);
  if MsiGetProductInfo(Code, Name, Buf, Len) = ERROR_SUCCESS then Result := CutAtNull(Buf) else Result := '';
end;

function RecordedLegacyFolder(PerMachine: Boolean): String;
begin
  Result := '';
  if PerMachine then
    RegQueryStringValue(HKLM64, LegacyRecordKey, 'InstallDir', Result)
  else
    RegQueryStringValue(HKCU, LegacyRecordKey, 'InstallDir', Result);
end;

procedure FindLegacyProducts;
var
  Index: DWORD;
  Buf: String;
  P: TLegacyProduct;
begin
  SetArrayLength(LegacyProducts, 0);
  Index := 0;
  while True do begin
    Buf := StringOfChar(#0, 39);
    if MsiEnumRelatedProducts(LegacyUpgradeCode, 0, Index, Buf) <> ERROR_SUCCESS then Break;
    P.Code := CutAtNull(Buf);
    P.Version := ProductInfo(P.Code, 'VersionString');
    P.PerMachine := ProductInfo(P.Code, 'AssignmentType') = '1';
    // 3.0.1 and later record the folder; every version set the Apps & features location on its first install.
    P.Folder := RecordedLegacyFolder(P.PerMachine);
    if P.Folder = '' then P.Folder := ProductInfo(P.Code, 'InstallLocation');
    if P.Folder <> '' then P.Folder := RemoveBackslashUnlessRoot(P.Folder);
    SetArrayLength(LegacyProducts, GetArrayLength(LegacyProducts) + 1);
    LegacyProducts[GetArrayLength(LegacyProducts) - 1] := P;
    Index := Index + 1;
  end;
  Log(Format('Installed MSI versions of PSD2Live: %d', [GetArrayLength(LegacyProducts)]));
end;

function InitializeSetup(): Boolean;
begin
  FindLegacyProducts;
  Result := True;
end;

// The installed MSI version's folder when there is no Inno Setup install to go back to (UsePreviousAppDir takes
// that). A per-machine folder is skipped by a current-user install, which could not write there.
function DefaultDir(Param: String): String;
var
  I: Integer;
begin
  for I := 0 to GetArrayLength(LegacyProducts) - 1 do
    if (LegacyProducts[I].Folder <> '') and (IsAdminInstallMode or not LegacyProducts[I].PerMachine) then begin
      Result := LegacyProducts[I].Folder;
      Exit;
    end;
  Result := ExpandConstant('{autopf}\PSD2Live');
end;

function StartsWithFolder(const Path, Folder: String): Boolean;
begin
  Result := (Folder <> '') and (Pos(Uppercase(AddBackslash(Folder)), Uppercase(Path)) = 1);
end;

// A PSD2Live.exe running from one of Folders. A process whose path cannot be read (another user's, to a current-user
// setup) counts, as it may be.
function AppRunningIn(const Folders: TArrayOfString): Boolean;
var
  Locator, Service, Processes, Process: Variant;
  I, J: Integer;
  Path: String;
begin
  Result := False;
  try
    Locator := CreateOleObject('WbemScripting.SWbemLocator');
    Service := Locator.ConnectServer('.', 'root\CIMV2');
    Processes := Service.ExecQuery('SELECT ExecutablePath FROM Win32_Process WHERE Name = ''PSD2Live.exe''');
    for I := 0 to Processes.Count - 1 do begin
      Process := Processes.ItemIndex(I);
      if VarIsNull(Process.ExecutablePath) then begin
        Result := True;
        Exit;
      end;
      Path := Process.ExecutablePath;
      for J := 0 to GetArrayLength(Folders) - 1 do
        if StartsWithFolder(Path, Folders[J]) then begin
          Result := True;
          Exit;
        end;
    end;
  except
    Log('Could not list running processes: ' + GetExceptionMessage);
  end;
end;

// Asks for PSD2Live to be closed until it is, or the user cancels; closing it through its window keeps the offer
// to save unsaved changes. Silent setups cancel.
function WaitForAppClosed(const Folders: TArrayOfString): Boolean;
begin
  Result := True;
  while AppRunningIn(Folders) do
    if SuppressibleMsgBox(CustomMessage('AppRunning'), mbError, MB_RETRYCANCEL, IDCANCEL) <> IDRETRY then begin
      Result := False;
      Exit;
    end;
end;

function RemoveLegacyProduct(const P: TLegacyProduct): String;
var
  Params: String;
  Code: Integer;
  Started: Boolean;
begin
  Result := '';
  WizardForm.PreparingLabel.Caption := FmtMessage(CustomMessage('RemovingLegacy'), [P.Version]);
  WizardForm.PreparingLabel.Visible := True;
  // Without the record, the RemoveFolderEx of 3.0.0 and earlier finds no folder and removes only its own files,
  // leaving projects saved in the folder; 3.0.1 and later never empty it.
  if P.PerMachine then RegDeleteKeyIncludingSubkeys(HKLM64, LegacyRecordKey)
  else RegDeleteKeyIncludingSubkeys(HKCU, LegacyRecordKey);
  // Windows Installer moves every file it removes into the volume's Config.Msi first, and where it cannot, the
  // uninstall fails ("Error writing to file ...\app\*.rbf", #14). The files go here, so it only unregisters. A
  // current-user setup cannot delete a per-machine install's files; msiexec removes them then.
  if (P.Folder <> '') and (IsAdmin or not P.PerMachine) then begin
    DelTree(P.Folder + '\app', True, True, True);
    DelTree(P.Folder + '\runtime', True, True, True);
    DeleteFile(P.Folder + '\PSD2Live.exe');
  end;
  Params := '/x ' + P.Code + ' /qn REBOOT=ReallySuppress';
  if ExpandConstant('{log}') <> '' then
    Params := Params + ' /l*v "' + ExtractFilePath(ExpandConstant('{log}')) + 'PSD2Live MSI removal.log"';
  Log('Removing PSD2Live ' + P.Version + ': msiexec ' + Params);
  // Removing a per-machine MSI needs administrator rights a current-user setup lacks; runas asks for them.
  if P.PerMachine and not IsAdmin then
    Started := ShellExec('runas', ExpandConstant('{sys}\msiexec.exe'), Params, '', SW_HIDE, ewWaitUntilTerminated, Code)
  else
    Started := Exec(ExpandConstant('{sys}\msiexec.exe'), Params, '', SW_HIDE, ewWaitUntilTerminated, Code);
  if not Started then Code := -1;
  Log(Format('msiexec exited with %d', [Code]));
  if (Code <> ERROR_SUCCESS) and (Code <> ERROR_UNKNOWN_PRODUCT) and (Code <> ERROR_SUCCESS_REBOOT_REQUIRED)
    and (Code <> ERROR_SUCCESS_REBOOT_INITIATED) then
    Result := FmtMessage(CustomMessage('LegacyRemoveFailed'), [P.Version, IntToStr(Code)]);
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Folders: TArrayOfString;
  I: Integer;
begin
  Result := '';
  SetArrayLength(Folders, GetArrayLength(LegacyProducts) + 1);
  Folders[0] := ExpandConstant('{app}');
  for I := 0 to GetArrayLength(LegacyProducts) - 1 do Folders[I + 1] := LegacyProducts[I].Folder;
  if not WaitForAppClosed(Folders) then begin
    Result := CustomMessage('AppStillRunning');
    Exit;
  end;
  // Before any file is copied: the MSI uninstall removes the files at its paths, which are the ones setup writes.
  for I := 0 to GetArrayLength(LegacyProducts) - 1 do begin
    Result := RemoveLegacyProduct(LegacyProducts[I]);
    if Result <> '' then Exit;
  end;
  RegDeleteKeyIfEmpty(HKLM64, 'Software\PSD2Live');
  RegDeleteKeyIfEmpty(HKCU, 'Software\PSD2Live');
end;

// Uninstall: deleting the user's data is the app's own --clear-user-data, which leaves it alone while an editor holds
// the store. It is asked for (No by default), or given as /CLEARUSERDATA=1 on the command line; it runs as the account
// that runs the uninstaller.
function InitializeUninstall(): Boolean;
var
  Folders: TArrayOfString;
begin
  SetArrayLength(Folders, 1);
  Folders[0] := ExpandConstant('{app}');
  Result := WaitForAppClosed(Folders);
  if not Result then Exit;
  ClearUserData := ExpandConstant('{param:CLEARUSERDATA|0}') = '1';
  if not ClearUserData and not UninstallSilent then
    ClearUserData := MsgBox(CustomMessage('ClearDataPrompt'), mbConfirmation, MB_YESNO or MB_DEFBUTTON2) = IDYES;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  Code: Integer;
begin
  if (CurUninstallStep = usUninstall) and ClearUserData then begin
    Exec(ExpandConstant('{app}\PSD2Live.exe'), '--clear-user-data', ExpandConstant('{app}'), SW_HIDE, ewWaitUntilTerminated, Code);
    Log(Format('--clear-user-data exited with %d', [Code]));
  end;
end;
