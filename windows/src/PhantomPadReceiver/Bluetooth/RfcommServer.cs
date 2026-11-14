using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

namespace PhantomPadReceiver.Bluetooth;

/// <summary>
/// Servidor RFCOMM/SPP (APIs WinRT nativas de Windows 10/11, sin dependencias
/// externas). Publica el servicio y entrega cada conexión entrante al motor.
/// Requiere que el teléfono esté emparejado con el PC.
/// </summary>
public sealed class RfcommServer
{
    /// <summary>Mismo UUID que RfcommTransport en la app Android.</summary>
    public const string ServiceUuidText = "8e6f4d2a-1b3c-4e5f-9a7d-2c3b4a5d6e7f";
    public const string ServiceName = "Phantom Pad Receiver";

    /// <summary>Se asigna desde el motor; recibe cada socket entrante.</summary>
    public Func<StreamSocket, Task>? ConnectionAccepted;

    public event Action<string>? Log;

    private RfcommServiceProvider? _provider;

    public bool IsAdvertising => _provider != null;

    public async Task StartAsync()
    {
        if (_provider != null) return;

        var provider = await RfcommServiceProvider.CreateAsync(
            RfcommServiceId.FromUuid(Guid.Parse(ServiceUuidText)));

        // Nombre de servicio visible en el SDP (atributo estándar 0x0100).
        var writer = new DataWriter();
        writer.WriteString(ServiceName);
        provider.SdpRawAttributes.Add(0x0100, writer.DetachBuffer());

        provider.ConnectionReceived += OnConnectionReceived;
        provider.StartAdvertising();
        _provider = provider;

        Log?.Invoke("Servicio RFCOMM publicado. Esperando la conexión del teléfono...");
    }

    private async void OnConnectionReceived(RfcommServiceProvider sender, RfcommConnectionEventArgs args)
    {
        try
        {
            string peer = "teléfono";
            try
            {
                peer = args.Socket.Information.RemoteAddress.DisplayName;
            }
            catch
            {
                // el nombre remoto puede no estar disponible en algunos adaptadores
            }
            Log?.Invoke("Conexión entrante desde " + peer);
            var handler = ConnectionAccepted;
            if (handler != null)
            {
                await handler(args.Socket);
            }
        }
        catch (Exception ex)
        {
            Log?.Invoke("Error al aceptar conexión: " + ex.Message);
        }
    }

    public void Stop()
    {
        var provider = _provider;
        _provider = null;
        if (provider == null) return;
        provider.ConnectionReceived -= OnConnectionReceived;
        try { provider.StopAdvertising(); } catch { /* al cerrar puede fallar y no importa */ }
    }
}
