package ru.minisip.app;

import android.Manifest;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import android.content.SharedPreferences;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

/**
 * Пустая главная активити: на ней лежит лента страниц-фрагментов (SettingsFragment).
 * Сами страницы чуть меньше экрана, по краям видна активити под ними.
 * <p>
 * Страниц столько, сколько создано. Листать нельзя, пока страница одна. Если на последней странице
 * настроена АТС (сервер и логин), справа появляется ещё одна: стоит на неё перелистнуть, и она
 * создаётся. Так же и дальше: новая страница создаёт следующую, только когда в ней самой настроена АТС.
 * Интерфейс собран в коде: ни одного xml-ресурса.
 */
public final class MainActivity extends AppCompatActivity {

    static final int REQ_VPN = 2;
    private static final int PAGER_ID = 0x4d530001;       // постоянный id: по нему ViewPager2 сохраняет страницу

    private ViewPager2 pager;
    private Pages pages;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        // экран можно зажигать поверх блокировки (датчик приближения будит телефон у лица)
        setShowWhenLocked(true);
        setTurnScreenOn(true);

        pager = new ViewPager2(this);
        pager.setId(PAGER_ID);
        pages = new Pages(this);
        pager.setAdapter(pages);
        pager.setUserInputEnabled(pages.getItemCount() > 1);   // одна страница — листать нечего
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                // перелистнули на следующую, ещё не созданную страницу: она создаётся
                if (position >= PageStore.count(MainActivity.this)) {
                    PageStore.setCount(MainActivity.this, position + 1);
                    pagesChanged();
                }
            }
        });

        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        root.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        if (b == null) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS}, 1);
            } else {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            }
        }
    }

    /** Настройки или число страниц изменились: пересчитать, сколько страниц можно листать. */
    void pagesChanged() {
        // не сразу: изменение могло прийти посреди расчёта экрана, а RecyclerView этого не любит
        pager.post(() -> {
            pages.refresh();
            pager.setUserInputEnabled(pages.getItemCount() > 1);
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 1) askBatteryOnce();
    }

    private void askBatteryOnce() {
        SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE);
        if (sp.getBoolean("batteryAsked", false)) return;
        sp.edit().putBoolean("batteryAsked", true).apply();
        requestIgnoreBatteryOptimizations();
    }

    private void requestIgnoreBatteryOptimizations() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;   // уже разрешено

        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);                    // системный диалог «Разрешить / Запретить»
        } catch (android.content.ActivityNotFoundException e) {
            // запасной вариант: общий список настроек оптимизации батареи
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    /** Ответ на системный запрос согласия на VPN: отдаём его странице, с которой запрос ушёл. */
    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_VPN) return;
        boolean ok = resultCode == RESULT_OK;
        // ViewPager2 кладёт страницу с тегом "f" + номер позиции; нажать кнопку можно только на текущей
        Fragment f = getSupportFragmentManager().findFragmentByTag("f" + pager.getCurrentItem());
        if (f instanceof SettingsFragment) {
            ((SettingsFragment) f).onVpnConsent(ok);
        } else if (!ok) {
            ((App) getApplication()).vpnDenied(pager.getCurrentItem());
        }
    }

    /** Лента страниц: созданные плюс, если в последней настроена АТС, одна пустая справа. */
    private static final class Pages extends FragmentStateAdapter {
        private final FragmentActivity host;
        private int count;

        Pages(FragmentActivity host) {
            super(host);
            this.host = host;
            this.count = slots();
        }

        private int slots() {
            int n = PageStore.count(host);
            return n + (PageStore.configured(host, n - 1) ? 1 : 0);
        }

        void refresh() {
            int now = slots();
            if (now == count) return;
            int old = count;
            count = now;
            if (now > old) notifyItemRangeInserted(old, now - old);
            else notifyItemRangeRemoved(now, old - now);
        }

        @Override
        public int getItemCount() {
            return count;
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            return SettingsFragment.create(position);
        }
    }
}
