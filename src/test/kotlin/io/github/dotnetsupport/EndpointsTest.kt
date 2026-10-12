package io.github.dotnetsupport

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.dotnetsupport.endpoints.Endpoint
import io.github.dotnetsupport.endpoints.EndpointRequests
import io.github.dotnetsupport.endpoints.EndpointScanner
import io.github.dotnetsupport.endpoints.EndpointsModel
import io.github.dotnetsupport.endpoints.HttpRequestGenerator

class EndpointsTest : BasePlatformTestCase() {
    private fun scan(code: String): List<String> = EndpointScanner.scan(code).map { "${it.method} ${it.route}" }

    // Program.cs of the `dotnet new webapiaot` template, the project the feature was tried on first
    private val todoApi = """
        var builder = WebApplication.CreateSlimBuilder(args);
        var app = builder.Build();

        var sampleTodos = new Todo[] { new(1, "Walk the dog") };

        var todosApi = app.MapGroup("/todos");
        todosApi.MapGet("/", () => sampleTodos);
        todosApi.MapGet("/{id}", (int id) =>
            sampleTodos.FirstOrDefault(a => a.Id == id) is { } todo
                ? Results.Ok(todo)
                : Results.NotFound());

        app.Run();
    """.trimIndent()

    fun testMinimalApiWithGroupVariable() {
        assertEquals(listOf("GET /todos", "GET /todos/{id}"), scan(todoApi))
        // the marker and the navigation point at the route string
        val endpoints = EndpointScanner.scan(todoApi)
        assertEquals(listOf("\"/\"", "\"/{id}\""), endpoints.map { todoApi.substring(it.offset, todoApi.indexOf(',', it.offset)) })
        assertEquals(listOf("id"), endpoints[1].parameters)
    }

    fun testGroupsChainsAndHandlers() {
        assertEquals(
            listOf(
                "GET /health", "GET /api/v1/orders", "POST /api/v1/orders", "DELETE /api/v1/orders/{id:guid}",
                "PUT /api/v1/admin/users/{id}", "GET /inline/x", "GET /files/{*path}", "HEAD /files/{*path}", "GET /healthz", "PATCH /plain",
            ),
            scan(
                """
                app.MapGet("/health", () => "ok").WithTags("infra");
                var api = app.MapGroup("/api").MapGroup("v1");
                RouteGroupBuilder orders = api.MapGroup("/orders").RequireAuthorization();
                orders.MapGet("", OrderHandlers.GetAll);
                orders.MapPost("/", Create).WithName("CreateOrder");
                orders.MapDelete("{id:guid}", async (Guid id, Db db) => { await db.Delete(id); return Results.NoContent(); });
                var admin = api.MapGroup("/admin");
                admin.MapGroup("/users").MapPut("/{id}", Update);
                app.MapGroup("/inline").WithTags("t").MapGet("/x", () => 1);
                app.MapMethods("/files/{*path}", new[] { "GET", "HEAD" }, Download);
                app.MapHealthChecks("/healthz");
                endpoints.MapPatch("/plain", Patch);
                // not endpoints: a dictionary-like Map call without a string template, a comment: app.MapGet("/commented", x)
                mapper.MapFrom(source);
                var text = "app.MapGet(\"/in-a-string\", x)";
                """.trimIndent()
            ),
        )
        val handlers = EndpointScanner.scan("app.MapGet(\"/a\", Handlers.GetAll);\napp.MapGet(\"/b\", () => 1);").map { it.handler }
        assertEquals(listOf("GetAll", null), handlers)
    }

    fun testControllers() {
        assertEquals(
            listOf(
                "GET /api/Orders", "GET /api/Orders/{id}", "POST /api/Orders", "PUT /api/Orders/{id}/items/{itemId:int}", "DELETE /legacy/orders/{id}",
                "GET /api/Orders/search", "GET /api/Orders/Export", "GET /plain", "GET /v2/plain",
            ),
            scan(
                """
                namespace Shop.Api.Controllers
                {
                    [ApiController]
                    [Route("api/[controller]")]
                    [Authorize(Roles = "admin")]
                    public class OrdersController : ControllerBase
                    {
                        private readonly Db _db;

                        [HttpGet]
                        public IEnumerable<Order> GetAll() => _db.Orders;

                        [HttpGet("{id}", Name = "GetOrder")]
                        public async Task<ActionResult<Order>> Get([FromRoute] int id) { return Ok(_db.Orders[id]); }

                        [HttpPost, Authorize]
                        public IActionResult Create([FromBody] Order order) { return Created(); }

                        [Microsoft.AspNetCore.Mvc.HttpPut("{id}/items/{itemId:int}")]
                        public IActionResult Replace(int id, int itemId) => NoContent();

                        [HttpDelete("/legacy/orders/{id}")]
                        public IActionResult Delete(int id) => NoContent();

                        [HttpGet]
                        [Route("search")]
                        public IActionResult Search(string q) => Ok();

                        [HttpGet("[action]")]
                        public IActionResult Export() => Ok();

                        [NonAction]
                        public void Helper() { }
                    }

                    [Route("plain")]
                    [Route("v2/plain")]
                    public class PlainController : Controller
                    {
                        [HttpGet] public IActionResult Index() => View();
                        public IActionResult NotRouted() => View();
                    }
                }
                """.trimIndent()
            ),
        )
        assertEquals("OrdersController.Get", EndpointScanner.scan("[Route(\"o\")] class OrdersController { [HttpGet(\"{id}\")] public int Get(int id) => 1; }").single().handler)
        assertEquals(emptyList<String>(), scan("class A { void M() { var x = items[HttpGet(1)]; client.HttpGet(\"/x\"); } }"))
    }

    fun testHttpRequests() {
        assertEquals("/todos/{{id}}/items/{{itemId}}/{{path}}", HttpRequestGenerator.path("/todos/{id:int}/items/{itemId?}/{*path}"))
        assertEquals("My_Shop_Api_HostAddress", HttpRequestGenerator.hostVariable("My.Shop-Api"))

        val get = Endpoint("GET", "/todos/{id}", 0, null)
        val post = Endpoint("POST", "/todos", 0, null)
        // a new file: the host variable, then the request
        val (created, offset) = HttpRequestGenerator.append("", get, "Shop", "http://localhost:5128")
        assertEquals("@Shop_HostAddress = http://localhost:5128\n\nGET {{Shop_HostAddress}}/todos/{{id}}\nAccept: application/json\n\n###\n", created)
        assertEquals(created.indexOf("GET "), offset)

        // the file of the project template: the variable is reused, requests are separated, a body is prepared for POST
        val template = "@Shop_HostAddress = http://localhost:5128\n\nGET {{Shop_HostAddress}}/todos/\nAccept: application/json\n\n###\n"
        val (withPost, postOffset) = HttpRequestGenerator.append(template, post, "Shop", "http://other:1")
        assertEquals(template.trimEnd() + "\n\nPOST {{Shop_HostAddress}}/todos\nAccept: application/json\nContent-Type: application/json\n\n{\n}\n\n###\n", withPost)
        assertEquals(withPost.indexOf("POST "), postOffset)
        assertEquals(1, Regex("@Shop_HostAddress").findAll(withPost).count { withPost.startsWith("@", it.range.first) })

        // asking for the same request again finds it instead of duplicating
        assertEquals(withPost to postOffset, HttpRequestGenerator.append(withPost, post, "Shop", null))
    }

    fun testDiscoveryAndRequestFile() {
        myFixture.addFileToProject("Api/Api.csproj", "<Project Sdk=\"Microsoft.NET.Sdk.Web\"/>")
        myFixture.addFileToProject("Api/Properties/launchSettings.json", """{ "profiles": { "https": { "commandName": "Project", "applicationUrl": "https://localhost:7001;http://localhost:5001" } } }""")
        myFixture.addFileToProject("Api/Program.cs", todoApi)
        myFixture.addFileToProject("Api/obj/Generated.cs", "app.MapGet(\"/generated\", x);")
        myFixture.addFileToProject("Lib/Lib.csproj", "<Project Sdk=\"Microsoft.NET.Sdk\"/>")
        myFixture.addFileToProject("Lib/Class1.cs", "class Class1 {}")
        myFixture.addFileToProject("All.slnx", "<Solution><Project Path=\"Api/Api.csproj\"/><Project Path=\"Lib/Lib.csproj\"/></Solution>")

        // only projects with endpoints, without build output
        val api = EndpointsModel.discover(project).single()
        assertEquals("Api", api.name)
        assertEquals(listOf("GET /todos", "GET /todos/{id}"), api.endpoints.map { "${it.endpoint.method} ${it.endpoint.route}" })
        assertEquals("https://localhost:7001", api.baseUrl)
        assertEquals("https://localhost:7001/todos", EndpointRequests.url(api, api.endpoints[0].endpoint))

        EndpointRequests.openRequest(project, api, api.endpoints[1].endpoint)
        val http = api.projectFile.parent.findChild("Api.http")!!
        assertEquals("@Api_HostAddress = https://localhost:7001\n\nGET {{Api_HostAddress}}/todos/{{id}}\nAccept: application/json\n\n###\n", VfsUtilCore.loadText(http).replace("\r\n", "\n")) // a new file gets the line separator of the OS

        // a globe in the gutter of each route
        myFixture.configureFromExistingVirtualFile(api.endpoints[0].file)
        assertEquals(2, myFixture.findAllGutters().size)
    }

    /** IntelliJ IDEA has an "Endpoints" tool window of its own: ours keeps the name, but not the id. */
    fun testToolWindowIdDoesNotClashWithTheOneOfIdea() {
        val pluginXml = javaClass.getResource("/META-INF/plugin.xml")!!.readText()
        assertFalse("<toolWindow id=\"Endpoints\"" in pluginXml)
        assertTrue("<toolWindow id=\"${io.github.dotnetsupport.endpoints.EndpointsToolWindowFactory.ID}\"" in pluginXml)
        assertEquals("Endpoints", io.github.dotnetsupport.endpoints.EndpointsToolWindowFactory.TITLE)
    }
}
