p = "app/src/main/java/ru/minisip/app/SettingsFragment.java"
s = open(p, encoding="utf-8").read()

def rep(old, new, name):
    global s
    assert old in s, "NOT FOUND: " + name
    s = s.replace(old, new, 1)

# 1) поля
rep(
"    private LogArea sipLog, callLog, vpnLog;",
"""    private LogArea sipLog, callLog, vpnLog;
    private LinearLayout refersPanel;
    private LinearLayout refersRows;""", "fields")

# 2) вызов title у SIP — с обработчиком Refers
rep(
'        sipLog = title(sipBlock.container, "SIP server settings", 0);',
'        sipLog = title(sipBlock.container, "SIP server settings", 0, this::toggleRefers);', "title call")

# 3) после панели лога SIP — панель Refers
rep(
"        sipBlock.container.addView(sipLog.panel, row(dp(8)));",
"""        sipBlock.container.addView(sipLog.panel, row(dp(8)));

        refersPanel = new LinearLayout(ctx);
        refersPanel.setOrientation(LinearLayout.VERTICAL);
        refersPanel.setVisibility(View.GONE);
        refersRows = new LinearLayout(ctx);
        refersRows.setOrientation(LinearLayout.VERTICAL);
        refersPanel.addView(refersRows);
        MaterialButton addRefer = smallButton(ctx, "+ Add refer");
        addRefer.setOnClickListener(v -> editRefer(-1));
        refersPanel.addView(addRefer, row(dp(4)));
        sipBlock.container.addView(refersPanel, row(dp(8)));""", "refers panel")

# 4) title(): перегрузка с onRefers
rep(
"""    private LogArea title(LinearLayout parent, String text, int top) {
        Context ctx = parent.getContext();""",
"""    private LogArea title(LinearLayout parent, String text, int top) {
        return title(parent, text, top, null);
    }

    private LogArea title(LinearLayout parent, String text, int top, Runnable onRefers) {
        Context ctx = parent.getContext();""", "title overload")

rep(
"""        more.setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(ctx, v);
            pm.getMenu().add("Log");
            pm.setOnMenuItemClickListener(item -> {
                boolean show = area.panel.getVisibility() == View.GONE;
                area.panel.setVisibility(show ? View.VISIBLE : View.GONE);
                return true;
            });
            pm.show();
        });""",
"""        more.setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(ctx, v);
            pm.getMenu().add("Log");
            if (onRefers != null) pm.getMenu().add("Refers");
            pm.setOnMenuItemClickListener(item -> {
                if (onRefers != null && "Refers".contentEquals(item.getTitle())) {
                    onRefers.run();
                    return true;
                }
                boolean show = area.panel.getVisibility() == View.GONE;
                area.panel.setVisibility(show ? View.VISIBLE : View.GONE);
                return true;
            });
            pm.show();
        });""", "title menu")

# 5) новые методы — вставить перед confirmDeletePage()
rep(
"    private void confirmDeletePage() {",
"""    /** Показ/скрытие таблицы Refers под блоком SIP (toggle, как у логов). */
    private void toggleRefers() {
        if (refersPanel == null) return;
        boolean show = refersPanel.getVisibility() == View.GONE;
        if (show) renderRefers();
        refersPanel.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** Перерисовка таблицы из PageStore. */
    private void renderRefers() {
        if (refersRows == null) return;
        Context ctx = requireContext();
        refersRows.removeAllViews();
        List<PageStore.Refer> list = PageStore.refers(ctx, page);
        for (int i = 0; i < list.size(); i++) {
            final int idx = i;
            PageStore.Refer r = list.get(i);
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView label = new TextView(ctx);
            label.setText(r.login + " | " + r.label());
            label.setTextAppearance(android.R.style.TextAppearance_Small);
            label.setPadding(dp(8), dp(8), dp(8), dp(8));
            label.setClickable(true);
            label.setOnClickListener(v -> confirmTransfer(r));
            row.addView(label, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView edit = new TextView(ctx);
            edit.setText("\\u270E");
            edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            edit.setPadding(dp(10), dp(6), dp(10), dp(6));
            edit.setClickable(true);
            edit.setOnClickListener(v -> editRefer(idx));
            row.addView(edit);

            TextView del = new TextView(ctx);
            del.setText("\\u2715");
            del.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            del.setPadding(dp(10), dp(6), dp(10), dp(6));
            del.setClickable(true);
            del.setOnClickListener(v -> deleteRefer(idx));
            row.addView(del);

            refersRows.addView(row);
        }
    }

    /** Тап по строке — confirm и blind transfer. */
    private void confirmTransfer(PageStore.Refer r) {
        if (app.state != App.TALK || app.lastPage != page) {
            Toast.makeText(requireContext(), "No active call", Toast.LENGTH_SHORT).show();
            return;
        }
        MainActivity.showCustomConfirmDialog(requireContext(),
                "Transfer call",
                "Transfer to " + r.login + " (" + r.label() + ")?",
                () -> app.transfer(page, r.login),
                null);
    }

    /** Диалог добавления (index < 0) или правки записи. */
    private void editRefer(int index) {
        Context ctx = requireContext();
        List<PageStore.Refer> list = PageStore.refers(ctx, page);
        PageStore.Refer existing = index >= 0 && index < list.size() ? list.get(index) : null;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad, pad, 0);

        EditText login = new EditText(ctx);
        login.setHint("Login");
        login.setInputType(InputType.TYPE_CLASS_TEXT);
        if (existing != null) login.setText(existing.login);
        box.addView(login);

        EditText name = new EditText(ctx);
        name.setHint("Name");
        name.setInputType(InputType.TYPE_CLASS_TEXT);
        if (existing != null) name.setText(existing.name);
        box.addView(name);

        new MaterialAlertDialogBuilder(ctx)
                .setTitle(existing == null ? "Add refer" : "Edit refer")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String l = login.getText().toString().trim();
                    String n = name.getText().toString().trim();
                    if (l.isEmpty()) return;
                    List<PageStore.Refer> cur = PageStore.refers(ctx, page);
                    if (existing == null) cur.add(new PageStore.Refer(l, n));
                    else if (index < cur.size()) cur.set(index, new PageStore.Refer(l, n));
                    PageStore.setRefers(ctx, page, cur);
                    renderRefers();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteRefer(int index) {
        Context ctx = requireContext();
        List<PageStore.Refer> list = PageStore.refers(ctx, page);
        if (index >= 0 && index < list.size()) list.remove(index);
        PageStore.setRefers(ctx, page, list);
        renderRefers();
    }

    private void confirmDeletePage() {""", "methods insert")

# 6) очистка в onDestroyView
rep(
"        vpnMainSpoilerContainer = vpnAdvancedSpoilerContainer = sipSpoilerContainer = null;",
"""        vpnMainSpoilerContainer = vpnAdvancedSpoilerContainer = sipSpoilerContainer = null;
        refersPanel = null;
        refersRows = null;""", "onDestroy cleanup")

open(p, "w", encoding="utf-8").write(s)
print("ok")
