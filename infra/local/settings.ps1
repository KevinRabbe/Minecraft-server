$LocalNetwork = @{
    PaperVersion = "26.1.2"
    VelocityVersion = "4.0.0"
    ProxyHost = "127.0.0.1"
    ProxyPort = 25565
    RuntimeRoot = Join-Path $PSScriptRoot "runtime"
    Servers = @(
        @{
            Id = "paper-01"
            Zone = "city"
            ZoneTemplate = "city-dev-v1"
            ZoneSoftCapacity = 100
            ZoneHardCapacity = 150
            Port = 25566
            Memory = "1024M"
        },
        @{
            Id = "paper-02"
            Zone = "starter-woods"
            ZoneTemplate = "starter-woods-dev-v1"
            ZoneSoftCapacity = 20
            ZoneHardCapacity = 25
            Port = 25567
            Memory = "768M"
        }
    )
}
