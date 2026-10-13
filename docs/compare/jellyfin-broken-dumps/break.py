import io, os
ROOT = 'C:/Users/dvislobokov/idea-dotnet-support/build/compare/jellyfin/'
S = 'Jellyfin.Api/Controllers/SearchController.cs'
U = 'Jellyfin.Api/Controllers/UserController.cs'
D = 'Jellyfin.Server.Implementations/Devices/DeviceManager.cs'
M = 'Jellyfin.Server.Implementations/Users/UserManager.cs'
V = 'Emby.Naming/Video/VideoResolver.cs'
W = 'Emby.Server.Implementations/Library/UserViewManager.cs'
I = 'MediaBrowser.Model/Dlna/StreamInfo.cs'
H = 'MediaBrowser.Model/Search/SearchHint.cs'
X = 'MediaBrowser.Model/Dto/UpdateUserItemDataDto.cs'

# (id, file, old, new, marker to find the line afterwards, what, expected Roslyn code)
B = [
 (1, S, "            Limit = limit,\n", "            Limit = searchTerm,\n", "Limit = searchTerm,", "string в int? в инициализаторе", "CS0029"),
 (2, S, "            IsKids = isKids,", "            IsKids = isKidz,", "IsKids = isKidz,", "опечатка в локале", "CS0103"),
 (3, S, "return new SearchHintResult(result.Items.Select(GetSearchHintResult).ToArray(), result.TotalRecordCount);", "return new SearchHintResult(result.Items.Select(GetSearchHintResult).ToArray());", "GetSearchHintResult).ToArray());", "недостающий аргумент конструктора", "CS7036"),
 (4, S, "        if (item.IsFolder)\n", "        if (item.MediaType)\n", "if (item.MediaType)", "enum в условии if", "CS0029"),
 (5, S, "result.Series = hasSeries.SeriesName;", "result.Series = hasSeries.SeriesNam;", "SeriesNam;", "опечатка в свойстве", "CS1061"),
 (6, S, "            EndDate = item.EndDate\n", "            EndDate = item.Name\n", "EndDate = item.Name", "string в DateTime? в инициализаторе", "CS0029"),
 (7, S, "                hint.ThumbImageTag = tag;\n", "                hint.ThumbImageTag = tag\n", "hint.ThumbImageTag = tag\n", "пропущена ;", "CS1002"),
 (8, S, "var channel = _libraryManager.GetItemById<BaseItem>(item.ChannelId);", "var channel = _libraryManager.GetItemById<string>(item.ChannelId);", "GetItemById<string>", "generic-аргумент не по ограничению", "CS0311"),
 (9, U, "        var users = Get(isHidden, isDisabled, false, false);", "        var users = Get(isHidden, isDisabled, false);", "Get(isHidden, isDisabled, false);", "недостающий аргумент метода", "CS7036"),
 (10, U, "        var result = _userManager.GetUserDto(user, HttpContext.GetNormalizedRemoteIP().ToString());\n        return result;", "        var result = _userManager.GetUserDto(user, HttpContext.GetNormalizedRemoteIP().ToString());\n        return result.Name.Length;", "return result.Name.Length;", "int в ActionResult<UserDto>", "CS0029"),
 (11, U, "            return NotFound();\n        }\n\n        await _sessionManager.RevokeUserTokens", "            return NotFond();\n        }\n\n        await _sessionManager.RevokeUserTokens", "return NotFond();", "опечатка в методе базового класса", "CS0103"),
 (12, U, "return await AuthenticateUserByName(request).ConfigureAwait(false);", "return await AuthenticateUserByName(request.ConfigureAwait(false);", "AuthenticateUserByName(request.ConfigureAwait(false);", "пропущена )", "CS1026"),
 (13, U, "    public ActionResult<IEnumerable<UserDto>> GetPublicUsers()\n    {\n", "    public ActionResult<IEnumerable<UserDto>> GetPublicUsers()\n    {\n        var hint = new SearchController(null!, null!, null!, null!).GetSearchHintResult(null!);\n", "GetSearchHintResult(null!);", "private-метод другого класса", "CS0122"),
 (14, D, "_capabilitiesMap[deviceId] = capabilities;", "_capabilitiesMap[deviceId] = deviceId;", "_capabilitiesMap[deviceId] = deviceId;", "string в ClientCapabilities в индексатор", "CS0029"),
 (15, D, "deviceOptions.CustomName = deviceName;", "deviceOptions.CustomName = deviceName.Length;", "deviceName.Length;", "int в string (и nullable)", "CS0029"),
 (16, D, "                _devices.TryAdd(device.Id, device);\n            }\n\n            return device;\n", "                _devices.TryAdd(device.Id, device);\n            }\n", "public async Task<Device> CreateDevice", "нет return в async Task<Device>", "CS0161"),
 (17, D, "            if (deviceId is null)\n            {\n                return new();\n", "            if (deviceId is null)\n            {\n                return new(1);\n", "return new(1);", "лишний аргумент в target-typed new", "CS1729"),
 (18, D, "            var count = devices.Count();\n", "            int count = devices;\n", "int count = devices;", "IEnumerable в int", "CS0029"),
 (19, D, "devices.Items.Select(device => ToDeviceInfo(device)).ToList());", "devices.Items.Select(device => ToDeviceInfo(device, 5)).ToList());", "ToDeviceInfo(device, 5)", "int вместо DeviceOptions", "CS1503"),
 (20, D, "if (user.HasPermission(PermissionKind.EnableAllDevices) || user.HasPermission(PermissionKind.IsAdministrator))", "if (PermissionKind.EnableAllDevices)", "if (PermissionKind.EnableAllDevices)", "enum в условии if", "CS0029"),
 (21, D, "                DateLastActivity = authInfo.DateLastActivity,\n                IconUrl = caps.IconUrl,", "                DateLastActivity = DateOnly.FromDateTime(authInfo.DateLastActivity),\n                IconUrl = caps.IconUrl,", "DateOnly.FromDateTime(authInfo.DateLastActivity)", "DateOnly в DateTime? в инициализаторе", "CS0029"),
 (22, D, "                Id = options.Id,\n                DeviceId = options.DeviceId,", "                Id = $\"{options.Id}\",\n                DeviceId = options.DeviceId,", "Id = $\"{options.Id}\",", "$\"\" в int", "CS0029"),
 (23, D, "            _deviceOptions.TryGetValue(id, out var deviceOption);\n", "            _deviceOptions.TryGetValue(id, out var deviceOption);\n            await Task.Delay(1);\n            var app = device.AppName;\n", "await Task.Delay(1);", "await в не-async методе", "CS4032"),
 (24, D, "            var app = device.AppName;\n", "            var app = device.AppName;\n", "var app = device.AppName;", "разыменование возможного null (FirstOrDefault)", "CS8602"),
 (25, V, "            bool isStub = false;", "            bool isStub = 0;", "bool isStub = 0;", "int в bool", "CS0029"),
 (26, V, "            int? year = null;", "            int? year = \"\";", "int? year = \"\";", "string в int?", "CS0029"),
 (27, V, "year = cleanDateTimeResult.Year;", "year = cleanDateTimeResult.Yeer;", "cleanDateTimeResult.Yeer;", "опечатка в свойстве", "CS1061"),
 (28, V, "return CleanDateTimeParser.Clean(name, namingOptions.CleanDateTimeRegexes);", "return CleanDateTimeParser.Clean(name);", "CleanDateTimeParser.Clean(name);", "недостающий аргумент статического метода", "CS7036"),
 (29, V, "return CleanStringParser.TryClean(name, namingOptions.CleanStringRegexes, out newName);", "return this.ToString() != null & CleanStringParser.TryClean(name, namingOptions.CleanStringRegexes, out newName);", "this.ToString()", "this в статическом методе", "CS0026"),
 (30, V, "        public static bool IsStubFile(string path, NamingOptions namingOptions)\n        {\n", "        public static bool IsStubFile(string path, NamingOptions namingOptions)\n        {\n            return false;\n", "            return false;\n            var extension = Path.GetExtension(path.AsSpan());\n            return namingOptions.StubFileExtensions", "недостижимый код", "CS0162"),
 (31, M, "            if (string.IsNullOrWhiteSpace(name))\n            {\n                throw new ArgumentException(\"Invalid username\", nameof(name));", "            if (string.IsNullOrWhiteSpace(name, true))\n            {\n                throw new ArgumentException(\"Invalid username\", nameof(name));", "IsNullOrWhiteSpace(name, true)", "лишний аргумент", "CS1501"),
 (32, M, "                    user.Username = newName;\n", "                    user.Username = userId;\n", "user.Username = userId;", "Guid в string", "CS0029"),
 (33, M, "            User newUser;\n", "            User newUser;\n            string newUser = name;\n", "string newUser = name;", "дубликат локала", "CS0128"),
 (34, M, "            User dbUser = null!;\n", "            User dbUser = null!;\n            int attempts;\n            attempts++;\n", "attempts++;", "неприсвоенная локальная", "CS0165"),
 (35, M, "            return ChangePassword(userId, string.Empty);\n", "            break;\n            return ChangePassword(userId, string.Empty);\n", "            break;\n            return ChangePassword", "break вне цикла", "CS0139"),
 (36, M, "            var castReceiverApplications = _serverConfigurationManager.Configuration.CastReceiverApplications;\n", "            var castReceiverApplications = _serverConfigurationManager.Configuration.CastReceiverApplications;\n            _eventManager = null!;\n", "_eventManager = null!;", "присваивание readonly-полю", "CS0191"),
 (37, W, "                if (parents.Count > 0)\n", "                if (parents.Count)\n", "if (parents.Count)", "int в условии if", "CS0029"),
 (38, W, "                    list.Add(folder);\n", "                    list.Add(folder.Name);\n", "list.Add(folder.Name);", "string вместо BaseItem", "CS1503"),
 (39, W, "            var uniqueId = parentId + \"subview\" + type;", "            Guid uniqueId = parentId + \"subview\" + type;", "Guid uniqueId = parentId", "string в Guid", "CS0029"),
 (40, W, "foreach (var viewType in new[] { CollectionType.movies, CollectionType.tvshows })", "foreach (var viewType in new[] { CollectionTyp.movies, CollectionType.tvshows })", "CollectionTyp.movies", "опечатка в типе", "CS0103"),
 (41, W, "            return items.Any(item => item.IsVisible(user));", "            return items.Any(item => item.IsVisible(user);", "item.IsVisible(user);", "пропущена )", "CS1026"),
 (42, W, "            var name = _localizationManager.GetServerLocalizedString(localizationKey);\n\n            return GetUserSubViewWithName", "            string name = null;\n\n            return GetUserSubViewWithName", "string name = null;", "null в не-nullable локал", "CS8600"),
 (43, I, "                double totalSeconds = RunTimeTicks.Value;", "                int totalSeconds = RunTimeTicks.Value;", "int totalSeconds = RunTimeTicks.Value;", "long в int", "CS0266"),
 (44, I, "            var stream = TargetVideoStream;\n\n            return VideoBitrate.HasValue", "            var stream = TargetVideoStreem;\n\n            return VideoBitrate.HasValue", "TargetVideoStreem;", "опечатка в свойстве", "CS0103"),
 (45, I, "            return !IsDirectStream\n                ? defaultValue", "            return IsDirectStream.Value\n                ? defaultValue", "IsDirectStream.Value", "член, которого нет у bool", "CS1061"),
 (46, I, "                return TargetVideoStream?.IsAnamorphic;\n            }\n\n            return false;", "                return TargetVideoStream?.IsAnamorphic;\n            }\n\n            return \"false\";", "return \"false\";", "string в bool?", "CS0029"),
 (47, H, "        public SearchHint()\n", "        public string BrokenLabel { get; set; }\n\n        public SearchHint()\n", "public SearchHint()", "не-nullable свойство, конструктор его не задаёт", "CS8618"),
 (48, X, "        public double? Rating { get; set; }\n", "        public double? Rating { get; set; }\n\n        public string Comment { get; set; }\n\n        public static readonly string DefaultKey;\n", "public string Comment", "не-nullable свойство и static readonly поле, конструктора нет", "CS8618"),
 (49, X, "        public static readonly string DefaultKey;\n", "        public static readonly string DefaultKey;\n", "DefaultKey;", "static readonly поле без значения", "CS8618"),
]

texts = {}
def get(f):
    if f not in texts:
        texts[f] = io.open(ROOT + f, encoding='utf-8-sig', newline='').read().replace('\r\n', '\n')
    return texts[f]
for n, f, old, new, mark, what, code in B:
    t = get(f)
    if old == new:
        continue
    assert t.count(old) == 1, (n, f, t.count(old))
    texts[f] = t.replace(old, new)
for f, t in texts.items():
    raw = io.open(ROOT + f, 'rb').read()
    bom = raw.startswith(b'\xef\xbb\xbf')
    crlf = b'\r\n' in raw
    out = t.replace('\n', '\r\n') if crlf else t
    io.open(ROOT + f, 'w', encoding='utf-8-sig' if bom else 'utf-8', newline='').write(out)
rows = ["# Поломки Jellyfin (2026-10-13)", "", "| № | Файл | Строка | Что сделано | Ожидаемый код Roslyn |", "|---|---|---|---|---|"]
for n, f, old, new, mark, what, code in B:
    t = texts[f]
    i = t.find(mark)
    assert i >= 0, (n, mark)
    line = t.count('\n', 0, i) + 1 + (mark[:i and 0].count('\n'))
    # the mark may start with blank-prefixed lines: point at the first line of the new text that differs
    first = mark.split('\n')
    line = t.count('\n', 0, i) + 1 + next((k for k, s in enumerate(first) if s.strip() and s not in old), 0)
    rows.append("| %d | `%s` | %d | %s | %s |" % (n, f, line, what, code))
io.open('C:/Users/dvislobokov/idea-dotnet-support/build/compare/broken/changes.md', 'w', encoding='utf-8').write('\n'.join(rows) + '\n')
print('\n'.join(rows))
