idf_component_register(
    SRCS
        "main.cpp"
        "wifi_setup.cpp"
        "provisioning_http.cpp"
    INCLUDE_DIRS "."
    REQUIRES
        esp_wifi
        esp_event
        esp_netif
        nvs_flash
        esp_http_server
        lwip
)
