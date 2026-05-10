# Spec: Prova de Conceito para Interceptação UDP (Switch LAN Play)

**Data:** 2026-05-08  
**Status:** Validado pelo Usuário  
**Objetivo:** Validar se o Android 13 (não roteado) consegue capturar pacotes UDP de um Nintendo Switch conectado via Hotspot, onde o Switch possui uma sub-rede estática (`10.13.0.0/16`) diferente da sub-rede padrão do Hotspot Android.

---

## 1. Arquitetura "Fake Gateway"

A solução utiliza a API `VpnService` para criar uma interface de rede virtual (`tun0`) que "sequestra" o tráfego destinado à rede `10.13.x.x`.

### Componentes Principais

1.  **Hotspot Link (L2):** 
    - Usa `WifiManager.startLocalOnlyHotspot()`.
    - Provê a conexão física via Wi-Fi.
    - O IP real do Android na interface Wi-Fi será ignorado pelo Switch, mas servirá como o "meio" de transporte dos frames.

2.  **VpnService (L3 Interceptor):**
    - **Endereço:** `10.13.37.1/32` (O celular se identifica como o Gateway esperado pelo Switch).
    - **Rota:** `10.13.0.0/16` (Força o kernel a rotear qualquer tráfego dessa rede para a nossa `tun0`).
    - **Configuração:** `blocking(true)`, `mtu(1500)`.

3.  **Raw Sniffer:**
    - Loop de leitura de baixo nível no `FileDescriptor` da VPN.
    - **Parsing Manual:** Extração de Protocolo (UDP = 17) e Portas diretamente dos bytes brutos do pacote IP.

---

## 2. Fluxo de Dados e Interceptação

1.  **Switch (10.13.0.100)** -> Envia pacote UDP para **Gateway (10.13.37.1)** porta **11451**.
2.  **Kernel Android** recebe o pacote via Wi-Fi.
3.  O Kernel identifica que o IP de destino `10.13.37.1` pertence à interface `tun0` (configurada pelo nosso `VpnService`).
4.  O pacote é entregue ao FD da `tun0`.
5.  O **Raw Sniffer** lê o buffer e loga o sucesso.

---

## 3. Especificações Técnicas (Kotlin)

### Hotspot Configuration
```kotlin
// Iniciar Hotspot
wifiManager.startLocalOnlyHotspot(object : LocalOnlyHotspotCallback() {
    override fun onStarted(hotspot: LocalOnlyHotspotReservation) {
        val config = hotspot.wifiConfiguration
        // Logar SSID e Senha para o usuário
    }
})
```

### VPN Configuration
```kotlin
val builder = VpnService.Builder()
builder.setSession("LanPlayPoC")
builder.addAddress("10.13.37.1", 32)
builder.addRoute("10.13.0.0", 16)
builder.allowBypass() // Permite conectividade externa para o app
val vpnInterface = builder.establish()
```

### Raw Parsing Logic
- `Offset 0`: Version & IHL (verificar se é 0x45).
- `Offset 9`: Protocol (17 para UDP).
- `Offset 12-15`: Source IP.
- `Offset 16-19`: Destination IP.
- `Offset [IHL*4]`: Source Port (2 bytes).
- `Offset [IHL*4 + 2]`: Destination Port (2 bytes).

---

## 4. Configuração do Switch (Fixa)

Para o teste, o Switch **deve** estar configurado manualmente:
- **SSID:** (O gerado pelo app)
- **Password:** (A gerada pelo app)
- **IP Address:** `10.13.0.100`
- **Subnet Mask:** `255.255.0.0`
- **Gateway:** `10.13.37.1`
- **DNS:** `8.8.8.8` (ou qualquer valor)

---

## 5. Critérios de Sucesso e Debug

### Sucesso
- Log no Logcat ou Console UI: `[SUCCESS] Captured UDP from 10.13.0.100:XXXX to 10.13.37.1:11451`.

### Falhas Conhecidas e Debug
- **DHCP Log:** Se o UDP não aparecer, o Sniffer deve logar qualquer tráfego nas portas 67/68 para verificar se o Switch está tentando obter IP via DHCP (o que indicaria que a configuração manual falhou ou o Switch resetou).
- **ARP Mismatch:** Se o kernel Android descartar o pacote antes do IP Layer (L2 drop) devido ao IP não bater com a sub-rede da interface Wi-Fi. Se isso ocorrer, a PoC falhou e precisaremos de root ou `VpnService` com `VpnService.setUnderlyingNetworks`.

---

## 6. Próximos Passos
1. Implementar `MainActivity` com UI básica de log.
2. Implementar `LanPlayVpnService`.
3. Implementar loop de leitura `RawPacketSniffer`.
4. Testar no Samsung M52.
