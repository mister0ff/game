package org.levimc.launcher.ui.activities;

import android.content.AttributionSource;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.levimc.launcher.core.minecraft.MinecraftActivity;
import org.levimc.launcher.core.minecraft.MinecraftActivityState;
import org.levimc.launcher.core.minecraft.MinecraftImportIntents;
import org.levimc.launcher.util.InstanceBackupManager;
import org.levimc.launcher.util.MinecraftUriHandler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class IntentHandler extends BaseActivity {
    private static final String TAG = "LeviMC_IntentHandler";
    private static final String MINECRAFT_PACKAGE = "com.mojang.minecraftpe";
    private static final String FILE_PROVIDER_AUTHORITY = "org.levimc.launcher.fileprovider"; // Ajuste para o seu authority real

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleDeepLink(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleDeepLink(intent);
    }

    private void handleDeepLink(Intent originalIntent) {
        Uri data = originalIntent.getData();

        if (data == null) {
            Log.w(TAG, "Intent sem data. Encerrando.");
            finish();
            return;
        }

        Log.d(TAG, "Processando URI: " + data.toString());

        try {
            // 1. Verifica se é um URI de arquivo local (file:// ou content://)
            if (isLocalFileUri(data)) {
                handleLocalFileUri(originalIntent, data);
                return;
            }

            // 2. Verifica se é um URI de deep link do Minecraft (minecraft://)
            if (MinecraftUriHandler.isMinecraftUri(data)) {
                handleMinecraftDeepLink(originalIntent, data);
                return;
            }

            // 3. Verifica se é um Intent de recurso do Minecraft (ex: .mcworld aberto pelo gerenciador de arquivos)
            if (MinecraftImportIntents.isMinecraftResourceIntent(this, originalIntent)) {
                // Tenta forward para o Minecraft oficial se estiver instalado
                if (MinecraftImportIntents.forwardToRunningMinecraft(this, originalIntent)) {
                    finish();
                    return;
                }
                
                // Fallback: Abre no seu launcher com permissões corretas
                Intent launcherIntent = new Intent(originalIntent);
                launcherIntent.setClass(this, MainActivity.class);
                // Garante que o launcher tenha permissão para ler o arquivo
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    launcherIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                startActivity(launcherIntent);
                finish();
                return;
            }

            // 4. Verifica se é um backup do launcher
            if (InstanceBackupManager.isBackupIntent(this, originalIntent)) {
                Intent launcherIntent = new Intent(originalIntent);
                launcherIntent.setClass(this, InstancesActivity.class);
                launcherIntent.putExtra(InstancesActivity.EXTRA_RESTORE_BACKUP_ON_OPEN, true);
                launcherIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(launcherIntent);
                finish();
                return;
            }

            // 5. Fallback genérico
            handleGenericIntent(originalIntent, data);

        } catch (Exception e) {
            Log.e(TAG, "Erro ao processar intent", e);
            finish();
        }
    }

    /**
     * Verifica se o URI é de um arquivo local (file:// ou content://)
     */
    private boolean isLocalFileUri(Uri uri) {
        return "file".equals(uri.getScheme()) || "content".equals(uri.getScheme());
    }

    /**
     * Lida com URIs de arquivo local, convertendo para FileProvider se necessário
     * para garantir que o libpairipcore.so aceite a entrega do conteúdo.
     */
    private void handleLocalFileUri(Intent originalIntent, Uri uri) {
        Log.d(TAG, "Tratando arquivo local: " + uri.toString());
        
        // Se for um content:// do nosso próprio FileProvider, usa diretamente
        if ("content".equals(uri.getScheme()) && FILE_PROVIDER_AUTHORITY.equals(uri.getAuthority())) {
            launchMinecraftWithFileProvider(uri, originalIntent);
            return;
        }

        // Se for um file:// ou content:// de outro app, copia para o cache e usa FileProvider
        File file = uriToFile(uri);
        if (file == null || !file.exists()) {
            Log.e(TAG, "Arquivo não encontrado para o URI: " + uri);
            // Fallback: tenta abrir diretamente no launcher
            Intent launcherIntent = new Intent(originalIntent);
            launcherIntent.setClass(this, MainActivity.class);
            launcherIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(launcherIntent);
            finish();
            return;
        }

        // Copia para o cache se necessário
        File cacheFile = copyToCache(file);
        if (cacheFile == null) {
            Log.e(TAG, "Falha ao copiar arquivo para cache");
            finish();
            return;
        }

        // Gera o FileProvider URI
        Uri fileProviderUri = FileProvider.getUriForFile(this, FILE_PROVIDER_AUTHORITY, cacheFile);
        launchMinecraftWithFileProvider(fileProviderUri, originalIntent);
        finish();
    }

    /**
     * Lida com deep links do Minecraft (minecraft://)
     * Adiciona AttributionSource para burlar o libpairipcore.so
     */
    private void handleMinecraftDeepLink(Intent originalIntent, Uri uri) {
        Log.d(TAG, "Handling Minecraft URI: " + uri.toString());
        
        MinecraftUriHandler.MinecraftUri parsedUri = MinecraftUriHandler.parse(uri);
        if (parsedUri != null) {
            Log.d(TAG, "Parsed Minecraft URI action: " + parsedUri.action);
        }
        
        Intent newIntent = new Intent(originalIntent);
        newIntent.putExtra("MINECRAFT_URI", uri.toString());
        newIntent.putExtra("MINECRAFT_URI_ACTION", parsedUri != null ? parsedUri.action : "");
        
        // Adiciona AttributionSource (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                AttributionSource attributionSource = new AttributionSource.Builder(getPackageName()).build();
                newIntent.setAttributionSource(attributionSource);
                Log.d(TAG, "AttributionSource aplicado: " + attributionSource);
            } catch (Exception e) {
                Log.w(TAG, "Falha ao aplicar AttributionSource", e);
            }
        }

        if (isMinecraftActivityRunning()) {
            newIntent.setClass(this, MinecraftActivity.class);
            newIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(newIntent);
        } else {
            newIntent.setClass(this, MainActivity.class);
            newIntent.putExtra("LAUNCH_WITH_URI", true);
            startActivity(newIntent);
        }
        
        finish();
    }

    /**
     * Lida com intents genéricos
     */
    private void handleGenericIntent(Intent originalIntent, Uri data) {
        Intent newIntent = new Intent(originalIntent);
        if (isMinecraftActivityRunning()) {
            newIntent.setClass(this, MinecraftActivity.class);
            newIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        } else {
            if (isMcRunning()) {
                newIntent.setClassName(this, "com.mojang.minecraftpe.Launcher");
            } else {
                newIntent.setClassName(this, "org.levimc.launcher.ui.activities.MainActivity");
            }
        }
        
        // Adiciona AttributionSource (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                AttributionSource attributionSource = new AttributionSource.Builder(getPackageName()).build();
                newIntent.setAttributionSource(attributionSource);
            } catch (Exception e) {
                Log.w(TAG, "Falha ao aplicar AttributionSource", e);
            }
        }

        startActivity(newIntent);
        finish();
    }

    /**
     * Dispara o Intent para o Minecraft com FileProvider URI
     */
    private void launchMinecraftWithFileProvider(Uri fileProviderUri, Intent originalIntent) {
        Intent intent = new Intent(Intent.ACTION_VIEW, fileProviderUri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        
        // Tenta abrir no Minecraft oficial se estiver instalado
        if (isMinecraftInstalled()) {
            intent.setPackage(MINECRAFT_PACKAGE);
            Log.d(TAG, "Abrindo no Minecraft oficial: " + fileProviderUri);
        } else {
            // Fallback: abre no seu launcher
            intent.setClass(this, MainActivity.class);
            Log.d(TAG, "Abrindo no launcher local: " + fileProviderUri);
        }
        
        // Adiciona AttributionSource (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                AttributionSource attributionSource = new AttributionSource.Builder(getPackageName()).build();
                intent.setAttributionSource(attributionSource);
            } catch (Exception e) {
                Log.w(TAG, "Falha ao aplicar AttributionSource", e);
            }
        }
        
        try {
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Log.e(TAG, "Minecraft não encontrado para abrir o URI", e);
            finish();
        }
    }

    /**
     * Converte um Uri para um File.
     * Lida com file:// e content://.
     */
    private File uriToFile(Uri uri) {
        try {
            if ("file".equals(uri.getScheme())) {
                return new File(uri.getPath());
            } else if ("content".equals(uri.getScheme())) {
                // Se for um content://, copia o stream para um arquivo temporário
                return copyStreamToFile(uri);
            } else {
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao converter URI para File", e);
            return null;
        }
    }

    /**
     * Copia um stream de um Uri para um arquivo no cacheDir.
     */
    private File copyStreamToFile(Uri uri) {
        try {
            String fileName = uri.getLastPathSegment();
            if (fileName == null || fileName.isEmpty()) {
                fileName = "levimc_" + System.currentTimeMillis() + ".mcworld";
            }
            
            File tempFile = new File(getCacheDir(), fileName);
            InputStream inputStream = getContentResolver().openInputStream(uri);
            if (inputStream != null) {
                FileOutputStream outputStream = new FileOutputStream(tempFile);
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                outputStream.close();
                inputStream.close();
                
                if (tempFile.exists() && tempFile.length() > 0) {
                    return tempFile;
                }
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Erro ao copiar stream para arquivo", e);
            return null;
        }
    }

    /**
     * Copia um arquivo para o cacheDir para garantir acesso via FileProvider.
     */
    private File copyToCache(File file) {
        try {
            if (file.getParentFile().equals(getCacheDir())) {
                return file; // Já está no cache
            } else {
                File destFile = new File(getCacheDir(), file.getName());
                java.nio.file.Files.copy(file.toPath(), destFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return destFile;
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao copiar arquivo para cache", e);
            return null;
        }
    }

    private boolean isMinecraftActivityRunning() {
        return MinecraftActivityState.isRunning(this);
    }

    private boolean isMcRunning() {
        try {
            Class<?> clazz = Class.forName("com.mojang.minecraftpe.Launcher", false, getClassLoader());
            Log.d(TAG, "Minecraft PE Launcher class exists!");
            return true;
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "Minecraft PE Launcher class not found.");
            return false;
        }
    }

    private boolean isMinecraftInstalled() {
        try {
            getPackageManager().getPackageInfo(MINECRAFT_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
