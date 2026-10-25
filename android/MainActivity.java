package ru.makeevka.fishshop;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

public class MainActivity extends StoreActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PushNotifications.initialize(this);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode
                != PushNotifications.PERMISSION_REQUEST) {
            return;
        }

        if (grantResults.length > 0
                && grantResults[0]
                == PackageManager.PERMISSION_GRANTED) {

            PushNotifications.initialize(this);

        } else {

            Toast.makeText(
                    this,
                    "Уведомления отключены. "
                            + "Их можно разрешить в настройках Android. "
                            + "Магазин продолжит работать.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }
}
// КОНЕЦ ФАЙЛА
