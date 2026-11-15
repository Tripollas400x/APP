using System.Runtime.InteropServices;
using System.Text;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Rfcomm;
using Windows.Devices.Radios;
using Windows.Foundation;
using Windows.Networking.Sockets;
using Windows.Storage.Streams;

namespace PhantomPadReceiver.Bluetooth;

/// <summary>Fallo de publicación del servicio con contexto real (paso, HRESULT).</summary>
public sealed class RfcommServerException : Exception
{
    public RfcommServerException(string message, Exception? inner = null) : base(message, inner) { }
}

/// <summary>
/// Servidor RFCOMM/SPP con las APIs WinRT nativas de Windows 10/11 (sin
/// dependencias externas). Publica el registro SDP y enlaza un
/// StreamSocketListener al servicio; las conexiones entrantes llegan por el
/// evento ConnectionReceived del listener y se entregan al motor.
/// Requiere que el teléfono esté emparejado con el PC.
///
/// Cada paso queda registrado ([BT] ...) y los fallos se reportan con el tipo
/// de excepción, el mensaje real y el HRESULT (los errores WinRT suelen llegar
/// con Message vacío: "ERROR:" sin datos ya no puede ocurrir).
///
/// Detalle importante sobre el atributo SDP 0x0100 (nombre de servicio): el
/// valor NO es la cadena en bruto; es un elemento SDP completo. Para texto
/// corto UTF-8: 0x25 (DataElement = TextString, tamaño = 1 byte de longitud)
/// + longitud en bytes + bytes UTF-8. Con la cadena en bruto el teléfono no
/// parsea el registro y el canal puede fallar al resolverse por UUID.
/// </summary>
public sealed class RfcommServer
{
    /// <summary>Mismo UUID que RfcommTransport en la app Android.</summary>
    public const string ServiceUuidText = "8e6f4d2a-1b3c-4e5f-9a7d-2c3b4a5d6e7f";
    public const string ServiceName = "Phantom Pad Receiver";

    /// <summary>Se asigna desde el motor; recibe cada socket entrante.</summary>
    public Func<StreamSocket, CancellationToken, Task>? ConnectionAccepted;

    public event Action<string>? Log;

    private volatile RfcommServiceProvider? _provider;
    private volatile StreamSocketListener? _listener;
    private TypedEventHandler<StreamSocketListener, StreamSocketListenerConnectionReceivedEventArgs>? _accept;
    private Radio? _radio;
    private TypedEventHandler<Radio, object>? _radioChanged;
    private TaskCompletionSource _radioLost = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private int _connections;

    public bool IsAdvertising => _provider != null;

    /// <summary>Descripción legible del último error de publicación (null si OK).</summary>
    public string? LastError { get; private set; }

    public async Task StartAsync(CancellationToken ct)
    {
        if (_provider != null) return;
        LastError = null;

        try
        {
            // ------------------------------------------------ 0) adaptador
            Log?.Invoke("[BT] Comprobando el adaptador Bluetooth...");
            var adapter = await BluetoothAdapter.GetDefaultAsync().AsTask(ct);
            if (adapter == null)
            {
                throw new RfcommServerException(
                    "[BT] Este PC no tiene adaptador Bluetooth (BluetoothAdapter.GetDefaultAsync devolvió null).");
            }
            if (!adapter.IsClassicSupported)
            {
                throw new RfcommServerException(
                    "[BT] El adaptador Bluetooth no soporta Bluetooth Classic (BR/EDR), necesario para RFCOMM/SPP.");
            }
            try
            {
                var btRadio = await adapter.GetRadioAsync().AsTask(ct);
                if (btRadio != null)
                {
                    _radio = btRadio;
                    var lost = _radioLost;
                    _radioChanged = (radio, _) =>
                    {
                        if (radio.State != RadioState.On) lost.TrySetResult();
                    };
                    btRadio.StateChanged += _radioChanged;
                    if (btRadio.State != RadioState.On)
                    {
                        throw new RfcommServerException(
                            $"[BT] Bluetooth {btRadio.State}. Actívalo en Configuración > Bluetooth.");
                    }
                }
                Log?.Invoke("[BT] Adaptador disponible (radio " + (btRadio?.Name ?? "Bluetooth") + ", estado " +
                            (btRadio?.State.ToString() ?? "desconocido") + ").");
            }
            catch (Exception ex) when (ex is RfcommServerException or OperationCanceledException)
            {
                throw;
            }
            catch (Exception ex)
            {
                // Consultar la radio es best-effort; si falla seguimos con el adaptador.
                Log?.Invoke("[BT] No se pudo consultar el estado de la radio: " + Detail(ex));
            }

            // ------------------------------------------------ 1) servicio
            Log?.Invoke("[BT] Creando servicio RFCOMM (UUID " + ServiceUuidText + ")...");
            RfcommServiceProvider provider;
            try
            {
                provider = await RfcommServiceProvider.CreateAsync(
                    RfcommServiceId.FromUuid(Guid.Parse(ServiceUuidText))).AsTask(ct);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                throw new RfcommServerException(
                    "[BT] RfcommServiceProvider.CreateAsync falló: " + Detail(ex) +
                    " — Pistas: 1) otro programa ya publicó ese UUID (cierra el otro Phantom Pad Receiver), " +
                    "2) Bluetooth apagado, 3) política del equipo sin servicios RFCOMM.", ex);
            }

            // ------------------------------------------------ 2) SDP
            Log?.Invoke("[BT] Configurando SDP (nombre de servicio)...");
            byte[] nameBytes = Encoding.UTF8.GetBytes(ServiceName);
            using var writer = new DataWriter();
            writer.WriteByte(0x25); // elemento SDP: tipo TextString, tamaño "1 byte de longitud"
            writer.WriteByte((byte)nameBytes.Length);
            writer.WriteBytes(nameBytes);
            provider.SdpRawAttributes.Add(0x0100, writer.DetachBuffer());

            // ------------------------------------------------ 3) listener
            var listener = new StreamSocketListener();
            _listener = listener;
            _accept = (_, args) => OnListenerConnectionReceived(listener, args, ct);
            listener.ConnectionReceived += _accept;
            Log?.Invoke("[BT] Enlazando listener al canal RFCOMM...");
            try
            {
                // BluetoothEncryptionAllowNullAuthentication acepta clientes
                // Android seguros (createRfcommSocketToServiceRecord) y sin
                // cifrado (createInsecureRfcommSocketToServiceRecord). Es el
                // nivel que usa la muestra oficial de Microsoft para RFCOMM.
                await listener.BindServiceNameAsync(
                    provider.ServiceId.AsString(),
                    SocketProtectionLevel.BluetoothEncryptionAllowNullAuthentication).AsTask(ct);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                throw new RfcommServerException(
                    "[BT] BindServiceNameAsync falló: " + Detail(ex) +
                    " — Pista: reinicia el Bluetooth del PC si acabas de cerrar otra sesión.", ex);
            }

            // ------------------------------------------------ 4) publicar
            ct.ThrowIfCancellationRequested();
            provider.StartAdvertising(listener);
            _provider = provider;
            Log?.Invoke("[BT] Servicio publicado correctamente. Esperando conexión Android " +
                        "(el teléfono debe estar EMPAREJADO con este PC)...");
        }
        catch (OperationCanceledException)
        {
            Stop();
            throw;
        }
        catch (RfcommServerException ex)
        {
            Stop();
            LastError = ex.Message;
            throw;
        }
        catch (Exception ex)
        {
            Stop();
            LastError = "[BT] No se pudo publicar el servicio RFCOMM: " + Detail(ex);
            throw new RfcommServerException(LastError, ex);
        }
    }

    private async void OnListenerConnectionReceived(
        StreamSocketListener listener, StreamSocketListenerConnectionReceivedEventArgs args, CancellationToken ct)
    {
        var socket = args.Socket;
        try
        {
            if (!ReferenceEquals(listener, _listener) || ct.IsCancellationRequested)
            {
                socket.Dispose();
                return;
            }
            int connection = Interlocked.Increment(ref _connections);
            string peer;
            try
            {
                peer = socket.Information.RemoteAddress.DisplayName;
            }
            catch
            {
                peer = "dispositivo " + connection; // el nombre remoto puede faltar según el adaptador
            }
            Log?.Invoke("[BT] Cliente conectado: " + peer + ".");
            var handler = ConnectionAccepted;
            if (handler == null)
            {
                Log?.Invoke("[BT] Sin manejador de conexiones: socket descartado.");
                socket.Dispose();
                return;
            }
            await handler(socket, ct);
        }
        catch (Exception ex)
        {
            Log?.Invoke("[BT] Error al aceptar conexión: " + Detail(ex));
            try { socket?.Dispose(); } catch { }
        }
    }

    public Task WaitForRadioLossAsync(CancellationToken ct) => _radioLost.Task.WaitAsync(ct);

    public void Stop()
    {
        var provider = _provider;
        var listener = _listener;
        _provider = null;
        _listener = null;
        var radio = _radio;
        _radio = null;
        var radioChanged = _radioChanged;
        _radioChanged = null;
        if (radio != null && radioChanged != null)
        {
            try { radio.StateChanged -= radioChanged; }
            catch (Exception ex) { Log?.Invoke("[BT] Cierre del monitor de radio: " + Detail(ex)); }
        }
        var accept = _accept;
        _accept = null;
        if (provider == null && listener == null) return;
        if (listener != null && accept != null)
        {
            try { listener.ConnectionReceived -= accept; }
            catch (Exception ex) { Log?.Invoke("[BT] Cierre del callback RFCOMM: " + Detail(ex)); }
        }
        try { provider?.StopAdvertising(); }
        catch (Exception ex) { Log?.Invoke("[BT] Cierre de SDP: " + Detail(ex)); }
        try { listener?.Dispose(); }
        catch (Exception ex) { Log?.Invoke("[BT] Cierre del listener: " + Detail(ex)); }
        Log?.Invoke("[BT] Servicio detenido.");
    }

    /// <summary>
    /// Descripción completa de una excepción: tipo, mensaje real (o "(sin
    /// mensaje)"), HRESULT y causa interna. Los errores WinRT/COM llegan casi
    /// siempre con Message vacío; el HRESULT es lo que los identifica.
    /// </summary>
    public static string Detail(Exception ex)
    {
        var sb = new StringBuilder();
        sb.Append(ex.GetType().Name).Append(": ")
          .Append(string.IsNullOrWhiteSpace(ex.Message) ? "(sin mensaje)" : ex.Message.Trim());
        sb.Append(" (HRESULT 0x").Append(ex.HResult.ToString("X8")).Append(')');
        if (ex is COMException)
        {
            sb.Append(" [COMException: error WinRT/COM; los códigos típicos son 0x800710DF=Bluetooth apagado, " +
                      "0x80070490=elemento no encontrado, 0x80070005=acceso denegado]");
        }
        if (ex.InnerException != null)
        {
            sb.Append(" <- ").Append(Detail(ex.InnerException));
        }
        return sb.ToString();
    }
}
