// Copyright 2024 The Outline Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.outline.vpn;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the list of apps excluded from the VPN tunnel (split tunneling).
 * Uses a JSON file for cross-process compatibility (the VPN service runs in a separate process).
 */
public class SplitTunnelManager {
    private static final Logger LOG = Logger.getLogger(SplitTunnelManager.class.getName());
    private static final String SPLIT_TUNNEL_FILE = "split_tunnel_apps.json";

    private final File configFile;

    public SplitTunnelManager(Context context) {
        // Use device-protected storage directory accessible from any process.
        this.configFile = new File(context.getFilesDir(), SPLIT_TUNNEL_FILE);
    }

    /** Returns the set of package names excluded from the VPN. */
    public Set<String> getDisallowedApps() {
        Set<String> apps = new HashSet<>();
        if (!configFile.exists()) {
            return apps;
        }
        try (FileInputStream fis = new FileInputStream(configFile);
             BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            JSONArray jsonArray = new JSONArray(sb.toString());
            for (int i = 0; i < jsonArray.length(); i++) {
                apps.add(jsonArray.getString(i));
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to read split tunnel config", e);
        }
        return apps;
    }

    /** Saves the set of package names to exclude from the VPN. */
    public void setDisallowedApps(Set<String> packageNames) {
        JSONArray jsonArray = new JSONArray();
        for (String pkg : packageNames) {
            jsonArray.put(pkg);
        }
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            fos.write(jsonArray.toString().getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.getFD().sync();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to write split tunnel config", e);
        }
    }
}
