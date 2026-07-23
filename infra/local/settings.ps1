$LocalNetwork = @{
    PaperVersion = "26.1.2"
    VelocityVersion = "4.0.0"
    ProxyHost = "127.0.0.1"
    ProxyPort = 25565
    RuntimeRoot = Join-Path $PSScriptRoot "runtime"
    Servers = @(
        @{ Id = "city-01";   Role = "CITY";   Port = 25566; Memory = "1024M" },
        @{ Id = "mine-01";   Role = "MINE";   Port = 25567; Memory = "768M" },
        @{ Id = "forest-01"; Role = "FOREST"; Port = 25568; Memory = "768M" },
        @{ Id = "farm-01";   Role = "FARM";   Port = 25569; Memory = "768M" },
        @{ Id = "nether-01"; Role = "NETHER"; Port = 25570; Memory = "768M" },
        @{ Id = "pvp-01";    Role = "PVP";    Port = 25571; Memory = "768M" },
        @{ Id = "war-01";    Role = "WAR";    Port = 25572; Memory = "768M" }
    )
}
