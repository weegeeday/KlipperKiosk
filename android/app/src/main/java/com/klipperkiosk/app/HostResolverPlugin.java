package com.klipperkiosk.app;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "HostResolver")
public class HostResolverPlugin extends Plugin {

    @PluginMethod
    public void resolve(PluginCall call) {
        String host = call.getString("host", "");
        String scheme = call.getString("scheme", "http");
        int port = call.getInt("port", 80);
        String path = call.getString("path", "");

        if (host == null || host.trim().isEmpty()) {
            call.reject("host is required");
            return;
        }

        getBridge().execute(() -> {
            try {
                HostResolver.Result res = HostResolver.resolveHost(
                    getContext(), host, scheme, port, path
                );
                JSObject result = new JSObject();
                result.put("url", res.url);
                result.put("ip", res.ip);
                result.put("source", res.source);
                result.put("host", res.host);
                call.resolve(result);
            } catch (Exception ex) {
                call.reject("Unable to resolve host: " + ex.getMessage());
            }
        });
    }
}
