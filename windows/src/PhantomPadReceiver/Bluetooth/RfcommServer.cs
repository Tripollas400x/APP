using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

namespace PhantomPadReceiver.Bluetooth;

/// <summary>
/// Servidor RFCOMM/SPP con las APIs WinRT nativas de Windows 10/11 (sin
/// dependencias externas). Publica el registro SDP y enlaza un
/// StreamSocketListener al servicio; las conexiones entrantes llegan por el
/// evento ConnectionReceived del listener y se entregan al motor.
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
    private StreamSocketListener? _listener;

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

        // El proveedor RFCOMM no expone eventos: las conexiones se reciben a
        // través de un StreamSocketListener enlazado al UUID del servicio.
        var listener = new StreamSocketListener();
        listener.ConnectionReceived += OnListenerConnectionReceived;
        await listener.BindServiceNameAsync(provider.ServiceId.AsString(), SocketProtectionLevel.PlainSocket);
        provider.StartAdvertising(listener);

        _listener = listener;
        _provider = provider;

        Log?.Invoke("Servicio RFCOMM publicado. Esperando la conexión del teléfono...");
    }

    private async void OnListenerConnectionReceived(StreamSocketListener sender, StreamSocketListenerConnectionReceivedEventArgs args)
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
        var listener = _listener;
        _provider = null;
        _listener = null;
        if (provider == null) return;
        if (listener != null) listener.ConnectionReceived -= OnListenerConnectionReceived;
        try { provider.StopAdvertising(); } catch { /* al cerrar puede fallar y no importa */ }
        try { listener?.Dispose(); } catch { }
    }
}
