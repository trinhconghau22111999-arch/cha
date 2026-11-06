using System;
using System.IO;
using System.Text.Json;
using System.Threading.Tasks;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.Wpf;

namespace TiviWidget;

/// <summary>
/// Chạy trang truyentep.html (Firebase + WebRTC, đúng như bản Android) trong một WebView2 ẩn,
/// và thay cầu nối "AndroidCam" bằng window.chrome.webview. Chỉ nối phần điều khiển từ xa:
/// remote, textEdit, keepAwake, toast. Các hàm file/ảnh được trả về giá trị "thất bại" (không port).
/// </summary>
internal sealed class RemoteBridge
{
    private const string VHost = "tivi.local";

    // Cầu nối giả lập AndroidCam chạy trước mọi script của trang
    private const string Shim = """
(function(){
  var post=function(o){try{window.chrome.webview.postMessage(JSON.stringify(o));}catch(e){}};
  var noop=function(){};
  window.AndroidCam={
    remote:function(json){post({t:'remote',json:String(json)});},
    textEdit:function(del,ins){post({t:'textEdit',del:del|0,ins:String(ins||'')});},
    keepAwake:function(on){post({t:'keepAwake',on:!!on});},
    toastMsg:function(m){post({t:'toast',msg:String(m)});},
    folder:function(){return '';},
    startFile:function(){return '';},
    appendChunk:function(){return false;},
    finishFile:function(){return false;},
    startReceived:function(){return '';},
    discardReceived:noop,
    publishDownloadAsync:function(){return false;},
    saveEdited:function(){return '';},
    publishDownload:function(){return false;},
    showOverlay:noop,
    close:noop
  };
})();
""";

    private readonly WebView2 _host;

    public event Action<string> RemoteJson;
    public event Action<int, string> TextEdit;
    public event Action<bool> KeepAwake;
    public event Action<string> Toast;

    public RemoteBridge(WebView2 host) => _host = host;

    public async Task StartAsync()
    {
        await _host.EnsureCoreWebView2Async();
        var core = _host.CoreWebView2;
        core.Settings.IsWebMessageEnabled = true;
        await core.AddScriptToExecuteOnDocumentCreatedAsync(Shim);
        core.SetVirtualHostNameToFolderMapping(
            VHost, Path.Combine(AppContext.BaseDirectory, "Assets", "web"),
            CoreWebView2HostResourceAccessKind.Allow);
        core.WebMessageReceived += (_, e) => Dispatch(e.TryGetWebMessageAsString());
        core.Navigate($"https://{VHost}/truyentep.html");
    }

    private void Dispatch(string msg)
    {
        try
        {
            using var doc = JsonDocument.Parse(msg);
            var r = doc.RootElement;
            switch (r.GetProperty("t").GetString())
            {
                case "remote":
                    RemoteJson?.Invoke(r.GetProperty("json").GetString() ?? "");
                    break;
                case "textEdit":
                    TextEdit?.Invoke(r.GetProperty("del").GetInt32(), r.GetProperty("ins").GetString() ?? "");
                    break;
                case "keepAwake":
                    KeepAwake?.Invoke(r.GetProperty("on").GetBoolean());
                    break;
                case "toast":
                    Toast?.Invoke(r.GetProperty("msg").GetString() ?? "");
                    break;
            }
        }
        catch (Exception) { }
    }
}
