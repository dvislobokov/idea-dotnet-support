using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
---
public class ${NAME} : IEntityTypeConfiguration<${BASE}>
{
    public void Configure(EntityTypeBuilder<${BASE}> builder)
    {
    }
}
