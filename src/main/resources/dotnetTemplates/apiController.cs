using Microsoft.AspNetCore.Mvc;
---
[ApiController]
[Route("api/[controller]")]
public class ${NAME} : ControllerBase
{
    [HttpGet]
    public IActionResult Get()
    {
        return Ok();
    }
}
