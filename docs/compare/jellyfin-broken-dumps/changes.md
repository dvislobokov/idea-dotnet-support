# Поломки Jellyfin (2026-10-13)

| № | Файл | Строка | Что сделано | Ожидаемый код Roslyn |
|---|---|---|---|---|
| 1 | `Jellyfin.Api/Controllers/SearchController.cs` | 106 | string в int? в инициализаторе | CS0029 |
| 2 | `Jellyfin.Api/Controllers/SearchController.cs` | 120 | опечатка в локале | CS0103 |
| 3 | `Jellyfin.Api/Controllers/SearchController.cs` | 127 | недостающий аргумент конструктора | CS7036 |
| 4 | `Jellyfin.Api/Controllers/SearchController.cs` | 159 | enum в условии if | CS0029 |
| 5 | `Jellyfin.Api/Controllers/SearchController.cs` | 178 | опечатка в свойстве | CS1061 |
| 6 | `Jellyfin.Api/Controllers/SearchController.cs` | 151 | string в DateTime? в инициализаторе | CS0029 |
| 7 | `Jellyfin.Api/Controllers/SearchController.cs` | 239 | пропущена ; | CS1002 |
| 8 | `Jellyfin.Api/Controllers/SearchController.cs` | 215 | generic-аргумент не по ограничению | CS0311 |
| 9 | `Jellyfin.Api/Controllers/UserController.cs` | 98 | недостающий аргумент метода | CS7036 |
| 10 | `Jellyfin.Api/Controllers/UserController.cs` | 142 | int в ActionResult<UserDto> | CS0029 |
| 11 | `Jellyfin.Api/Controllers/UserController.cs` | 161 | опечатка в методе базового класса | CS0103 |
| 12 | `Jellyfin.Api/Controllers/UserController.cs` | 201 | пропущена ) | CS1026 |
| 13 | `Jellyfin.Api/Controllers/UserController.cs` | 111 | private-метод другого класса | CS0122 |
| 14 | `Jellyfin.Server.Implementations/Devices/DeviceManager.cs` | 71 | string в ClientCapabilities в индексатор | CS0029 |
| 15 | `Jellyfin.Server.Implementations/Devices/DeviceManager.cs` | 88 | int в string (и nullable) | CS0029 |
| 16 | `Jellyfin.Server.Implementations/Devices/DeviceManager.cs` | 98 | нет return в async Task<Device> | CS0161 |
| 17 | `Jellyfin.Server.Implementations/Devices/DeviceManager.cs` | 125 | лишний аргумент в target-typed new | CS1729 |
| 18 | `Jellyfin.Server.Implementations/Devices/DeviceManager.cs` | 154 | IEnumerable в int | CS0029 |
| 19 | `Jellyfin.Server.Implementations/Devices/DeviceManager.cs` | 177 | int вместо DeviceOptions | CS1503 |
| 20 | `Jellyfin.Server.Implementations/Devices/DeviceManager.cs` | 242 | enum в условии if | CS0029 |
| 21 | `Jellyfin.Server.Implementations/Devices/DeviceManager.cs` | 264 | DateOnly в DateTime? в инициализаторе | CS0029 |
| 22 | `Jellyfin.Server.Implementations/Devices/DeviceManager.cs` | 274 | $"" в int | CS0029 |
| 23 | `Jellyfin.Server.Implementations/Devices/DeviceManager.cs` | 138 | await в не-async методе | CS4032 |
| 24 | `Jellyfin.Server.Implementations/Devices/DeviceManager.cs` | 139 | разыменование возможного null (FirstOrDefault) | CS8602 |
| 25 | `Emby.Naming/Video/VideoResolver.cs` | 56 | int в bool | CS0029 |
| 26 | `Emby.Naming/Video/VideoResolver.cs` | 85 | string в int? | CS0029 |
| 27 | `Emby.Naming/Video/VideoResolver.cs` | 91 | опечатка в свойстве | CS1061 |
| 28 | `Emby.Naming/Video/VideoResolver.cs` | 158 | недостающий аргумент статического метода | CS7036 |
| 29 | `Emby.Naming/Video/VideoResolver.cs` | 147 | this в статическом методе | CS0026 |
| 30 | `Emby.Naming/Video/VideoResolver.cs` | 133 | недостижимый код | CS0162 |
| 31 | `Jellyfin.Server.Implementations/Users/UserManager.cs` | 156 | лишний аргумент | CS1501 |
| 32 | `Jellyfin.Server.Implementations/Users/UserManager.cs` | 201 | Guid в string | CS0029 |
| 33 | `Jellyfin.Server.Implementations/Users/UserManager.cs` | 344 | дубликат локала | CS0128 |
| 34 | `Jellyfin.Server.Implementations/Users/UserManager.cs` | 431 | неприсвоенная локальная | CS0165 |
| 35 | `Jellyfin.Server.Implementations/Users/UserManager.cs` | 422 | break вне цикла | CS0139 |
| 36 | `Jellyfin.Server.Implementations/Users/UserManager.cs` | 459 | присваивание readonly-полю | CS0191 |
| 37 | `Emby.Server.Implementations/Library/UserViewManager.cs` | 102 | int в условии if | CS0029 |
| 38 | `Emby.Server.Implementations/Library/UserViewManager.cs` | 93 | string вместо BaseItem | CS1503 |
| 39 | `Emby.Server.Implementations/Library/UserViewManager.cs` | 189 | string в Guid | CS0029 |
| 40 | `Emby.Server.Implementations/Library/UserViewManager.cs` | 97 | опечатка в типе | CS0103 |
| 41 | `Emby.Server.Implementations/Library/UserViewManager.cs` | 177 | пропущена ) | CS1026 |
| 42 | `Emby.Server.Implementations/Library/UserViewManager.cs` | 196 | null в не-nullable локал | CS8600 |
| 43 | `MediaBrowser.Model/Dlna/StreamInfo.cs` | 616 | long в int | CS0266 |
| 44 | `MediaBrowser.Model/Dlna/StreamInfo.cs` | 639 | опечатка в свойстве | CS0103 |
| 45 | `MediaBrowser.Model/Dlna/StreamInfo.cs` | 659 | член, которого нет у bool | CS1061 |
| 46 | `MediaBrowser.Model/Dlna/StreamInfo.cs` | 683 | string в bool? | CS0029 |
| 47 | `MediaBrowser.Model/Search/SearchHint.cs` | 18 | не-nullable свойство, конструктор его не задаёт | CS8618 |
| 48 | `MediaBrowser.Model/Dto/UpdateUserItemDataDto.cs` | 16 | не-nullable свойство и static readonly поле, конструктора нет | CS8618 |
| 49 | `MediaBrowser.Model/Dto/UpdateUserItemDataDto.cs` | 18 | static readonly поле без значения | CS8618 |
