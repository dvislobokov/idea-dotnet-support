using Microsoft.EntityFrameworkCore;
---
public class ${NAME} : DbContext
{
    public ${NAME}(DbContextOptions<${NAME}> options) : base(options)
    {
    }

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.ApplyConfigurationsFromAssembly(typeof(${NAME}).Assembly);
    }
}
