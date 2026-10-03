// Aspire, step 1 of the plugin: Debug of this AppHost attaches the debugger to the services DCP starts (here: Web).
// BP:aspire-apphost — a stop in the AppHost itself, before DCP starts anything; EXPECT: only the AppHost tab, no Web tab yet
var builder = DistributedApplication.CreateBuilder(args);

builder.AddProject<Projects.Web>("web");

builder.Build().Run();
