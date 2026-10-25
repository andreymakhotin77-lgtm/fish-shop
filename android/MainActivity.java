package ru.makeevka.fishshop;

import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

public class MainActivity extends StoreActivity {

    private OrderPushSubscriptions orderPush;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PushNotifications.createChannel(this);

        orderPush = new OrderPushSubscriptions(this);
        orderPush.start();

        PushNotifications.initialize(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (orderPush != null) {
            orderPush.refresh();
        }
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

        if (
                requestCode !=
                        PushNotifications.PERMISSION_REQUEST
        ) {
            return;
        }

        if (
                grantResults.length > 0 &&
                grantResults[0] ==
                        PackageManager.PERMISSION_GRANTED
        ) {
            PushNotifications.initialize(this);

            if (orderPush != null) {
                orderPush.refresh();
            }

        } else {
            Toast.makeText(
                    this,
                    "Уведомления отключены. " +
                            "Их можно разрешить " +
                            "в настройках Android. " +
                            "Магазин продолжит работать.",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    @Override
    protected void onDestroy() {
        if (orderPush != null) {
            orderPush.close();
            orderPush = null;
        }

        super.onDestroy();
    }
}
// КОНЕЦ ФАЙЛА
