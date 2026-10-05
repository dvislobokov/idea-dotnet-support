cd "$(git rev-parse --show-toplevel)"
. tools/ui-robot/rider/analysis/rj.sh
sp() {
  echo; echo "################ $1 ($2)"
  activate
  rj $R/tools/ui-robot/rider/analysis/settings_page.js "s|__MODE__|open|" "s|__ID__|$2|" "s|__TABS__|no|" > /dev/null
  sleep ${WAITS:-6}
  rj $R/tools/ui-robot/rider/analysis/settings_page.js "s|__MODE__|dump|" "s|__ID__||" "s|__TABS__|${TABS:-no}|"
  if [ -n "$3" ]; then python $R/tools/ui-robot/robot.py shot "$R/docs/rider-analysis/img/$3" "//div[@class='MyDialog']" > /dev/null; fi
  rj $R/tools/ui-robot/rider/analysis/settings_page.js "s|__MODE__|close|" "s|__ID__||" "s|__TABS__|no|" > /dev/null
  sleep 1
}
WAITS=10 TABS=all sp "Editor | Code Style | C#" "preferences.sourceCode.C#" 40-settings-code-style-csharp.png
sp "Editor | Inlay Hints (general)" "inlay.hints" ""
sp "Editor | Inlay Hints | C#" "inlay.hints.RiderInlayHintsCSharpConfigurableGroup" ""
sp "Editor | Inlay Hints | C# | Parameter Name Hints" "CSharpParameterNameHintsOptions" 41-settings-inlay-parameter-names.png
sp "Editor | Inlay Hints | C# | Type Name Hints" "CSharpTypeNameHintsOptions" 42-settings-inlay-type-names.png
sp "Editor | Inlay Hints | C# | Other" "CSharpOtherInlayHintsOptions" ""
sp "Editor | Inlay Hints | C# | Type Conversion Hints" "CSharpTypeConversionHintsOptions" ""
sp "Editor | Code Vision" "CodeLensConfigurable" 43-settings-code-vision.png
sp "Editor | General | Code Completion" "editor.preferences.completion" 44-settings-code-completion.png
sp "Editor | General | Code Completion | Popup" "editor.preferences.completion.popup" ""
sp "Editor | General | Code Completion | Inline" "editor.preferences.completion.inline" ""
sp "Editor | General | Auto Import" "editor.preferences.import" ""
sp "Editor | General | Typing Assistance" "editor.preferences.smartKeys_rider" 45-settings-typing-assistance.png
sp "Editor | General | Postfix Completion" "reference.settingsdialog.IDE.editor.postfix.templates" 46-settings-postfix.png
sp "Editor | General | Gutter Icons" "editor.preferences.gutterIcons" ""
WAITS=10 sp "Editor | Live Templates | C#" "RiderCSharpLiveTemplatesSettingsId" 47-settings-live-templates-csharp.png
sp "Editor | Inspection Settings" "CodeInspectionSettingsId" 48-settings-inspection-settings.png
WAITS=10 sp "Editor | Inspection Settings | Inspection Severity | C#" "Inspections-C#" 49-settings-inspection-severity-csharp.png
sp "Editor | Members Generation" "MemberGeneratorId" ""
sp "Editor | Context Actions" "preferences.intentionPowerPack" ""
sp "Editor | Code Cleanup | C#" "CSharpCodeCleanupOptionsPageProvider" ""
sp "Editor | Search and Navigation" "UsagesOptionsPageId" ""
sp "Editor | Color Scheme | C#" "reference.settingsdialog.IDE.editor.colors.C#" 50-settings-color-scheme-csharp.png
sp "Build | Toolset and Build" "SolutionBuilderGeneralOptionsPage" 51-settings-toolset-and-build.png
sp "Build | NuGet" "RiderNuGetOptionsPageId" ""
sp "Build | Unit Testing" "RiderUnitTestingPageId" ""
sp "Environment" "preferences.environmentSetup" ""
sp "Languages & Frameworks | C# Interactive" "preferences.CSharpInteractiveConfiguration" ""
