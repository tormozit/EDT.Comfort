package tormozit;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferenceConverter;
import org.eclipse.swt.graphics.RGB;

/**
 * Общие описания видимых настроек: подписи страниц и полный состав заявки.
 * Служебные ключи, кэши и состояние расположения окон сюда не входят.
 * Новое поле страницы объявляется здесь и использует {@link #label(String)}.
 * Значения читаются из хранилища вместе с defaults без создания страниц SWT.
 */
final class ComfortVisibleSettings
{
    private static enum Kind { FLAG, TEXT, THEME_COLOR, COLOR, STYLE, EXCLUDED_TYPES }

    private static final String[] INDENT_GUIDE_STYLES = {
        "Сплошная", "Штрих", "Точки", "Штрих-точка", "Штрих-точка-точка" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
    };

    private static record Setting(String section, String key, String label, Kind kind) {}

    private static final List<Setting> SETTINGS = List.of(
        new Setting("Комфорт", ComfortSettings.PREF_IMPROVE_DEBUGGER_WINDOWS, //$NON-NLS-1$
            "Улучшать окна отладчика", Kind.FLAG), //$NON-NLS-1$
        new Setting("Комфорт", ComfortSettings.PREF_SUPPRESS_MIN_PLATFORM_BUILD, //$NON-NLS-1$
            "Подавлять требование минимальной сборки платформы", Kind.FLAG), //$NON-NLS-1$
        new Setting("Комфорт", ComfortSettings.PREF_CONTROL_MD_REFERENCES, //$NON-NLS-1$
            "Контролировать ссылки метаданных в ключевых операциях", Kind.FLAG), //$NON-NLS-1$
        new Setting("Комфорт", ComfortSettings.PREF_MD_EDITOR_VERTICAL_TABS, //$NON-NLS-1$
            "Вертикальные вкладки в редакторе объекта", Kind.FLAG), //$NON-NLS-1$
        new Setting("Комфорт", ComfortSettings.PREF_REPLACE_LIST_FILTERS, //$NON-NLS-1$
            "Улучшать списки", Kind.FLAG), //$NON-NLS-1$
        new Setting("Комфорт", ComfortSettings.PREF_GROUP_COMMON_MODULES_ENABLED, //$NON-NLS-1$
            "Группировать общие модули в навигаторе по имени", Kind.FLAG), //$NON-NLS-1$
        new Setting("Комфорт", ComfortSettings.PREF_GROUP_COMMON_MODULES_SUFFIXES_1, //$NON-NLS-1$
            "Суффиксы набора 1 (через запятую):", Kind.TEXT), //$NON-NLS-1$
        new Setting("Комфорт", ComfortSettings.PREF_GROUP_COMMON_MODULES_SUFFIXES_2, //$NON-NLS-1$
            "Суффиксы набора 2 (через запятую):", Kind.TEXT), //$NON-NLS-1$
        new Setting("Комфорт", ComfortSettings.PREF_ALPHABETIC_COMMON_NODE_ENABLED, //$NON-NLS-1$
            "Сортировать по алфавиту ветку «Общие» в деревьях конфигурации", Kind.FLAG), //$NON-NLS-1$
        new Setting("Комфорт", ComfortSettings.PREF_FILTER_MATCH_COLOR, //$NON-NLS-1$
            "Цвет фильтра:", Kind.THEME_COLOR), //$NON-NLS-1$
        new Setting("Комфорт", ComfortSettings.PREF_CTRL_CLICK_SELECT_WORD, //$NON-NLS-1$
            "Ctrl+клик выделяет слово", Kind.FLAG), //$NON-NLS-1$
        new Setting("Комфорт", ComfortSettings.PREF_MOXEL_FAST_CELL_NAVIGATION, //$NON-NLS-1$
            "Ускорить перерисовку табличного документа", Kind.FLAG), //$NON-NLS-1$
        new Setting("Комфорт", ComfortSettings.PREF_DEBUG_LOG, //$NON-NLS-1$
            "Вести журнал", Kind.FLAG), //$NON-NLS-1$
        new Setting("Комфорт", ComfortSettings.PREF_RECORD_STALL_STACKS, //$NON-NLS-1$
            "Записывать стеки при зависаниях", Kind.FLAG), //$NON-NLS-1$
        new Setting("Редактор кода", ContentAssistSettings.PREF_ENABLED, //$NON-NLS-1$
            "Автооткрытие подсказок при вводе", Kind.FLAG), //$NON-NLS-1$
        new Setting("Редактор кода", ComfortSettings.PREF_ECONOMICAL_METHOD_COMMENT, //$NON-NLS-1$
            "Экономная генерация комментария к методу", Kind.FLAG), //$NON-NLS-1$
        new Setting("Редактор кода", ContentAssistSettings.PREF_TIMEOUT, //$NON-NLS-1$
            "Автооткрытие: Задержка (мс)", Kind.TEXT), //$NON-NLS-1$
        new Setting("Редактор кода", ComfortSettings.PREF_NAPARNIK_ASSIST_ROW_ENABLED, //$NON-NLS-1$
            "Предложение Напарника наверху списка автодополнения", Kind.FLAG), //$NON-NLS-1$
        new Setting("Редактор кода", ComfortSettings.PREF_SERVER_CALL_HIGHLIGHTING_ENABLED, //$NON-NLS-1$
            "Подсвечивать серверные вызовы", Kind.FLAG), //$NON-NLS-1$
        new Setting("Редактор кода", ComfortSettings.PREF_SERVER_CALL_HIGHLIGHTING_COLOR, //$NON-NLS-1$
            "Цвет серверных вызовов:", Kind.THEME_COLOR), //$NON-NLS-1$
        new Setting("Редактор кода", ComfortSettings.PREF_SERVER_CALL_CONTEXT_HIGHLIGHTING_COLOR, //$NON-NLS-1$
            "Цвет серверных вызовов с контекстом:", Kind.THEME_COLOR), //$NON-NLS-1$
        new Setting("Редактор кода", ComfortSettings.PREF_IMPLICIT_VARIABLE_HIGHLIGHTING_ENABLED, //$NON-NLS-1$
            "Подсвечивать создаваемые переменные", Kind.FLAG), //$NON-NLS-1$
        new Setting("Редактор кода", ComfortSettings.PREF_IMPLICIT_VARIABLE_HIGHLIGHTING_COLOR, //$NON-NLS-1$
            "Цвет создаваемых переменных:", Kind.THEME_COLOR), //$NON-NLS-1$
        new Setting("Редактор кода", ComfortSettings.PREF_QUERY_LITERAL_HIGHLIGHTING_ENABLED, //$NON-NLS-1$
            "Синтаксическая раскраска запросов в модуле", Kind.FLAG), //$NON-NLS-1$
        new Setting("Редактор кода", ComfortSettings.PREF_BRACKET_CONTENT_HINT_ENABLED, //$NON-NLS-1$
            "Отображать начало конструкции в её конце", Kind.FLAG), //$NON-NLS-1$
        new Setting("Редактор кода", ComfortSettings.PREF_BRACKET_CONTENT_HINT_MIN_LINES, //$NON-NLS-1$
            "Минимальное расстояние в строках", Kind.TEXT), //$NON-NLS-1$
        new Setting("Редактор кода", ComfortSettings.PREF_COMPILE_CONTEXT_STATUS_WIDTH, //$NON-NLS-1$
            "Ширина индикатора условий компиляции", Kind.TEXT), //$NON-NLS-1$
        new Setting("Редактор кода", ComfortSettings.PREF_AUTO_COLLAPSE_REGIONS, //$NON-NLS-1$
            "Автоматически сворачиваемые области", Kind.TEXT), //$NON-NLS-1$
        new Setting("Встроенный язык → Сворачивание/Отображение участка кода", ComfortSettings.PREF_FOLD_TEXT_LITERALS, //$NON-NLS-1$
            "Группировать большой текстовый литерал", Kind.FLAG), //$NON-NLS-1$
        new Setting("Встроенный язык → Сворачивание/Отображение участка кода", ComfortSettings.PREF_FOLD_BATCH_QUERIES, //$NON-NLS-1$
            "Группировать запрос пакета", Kind.FLAG), //$NON-NLS-1$
        new Setting("Встроенный язык → Сворачивание/Отображение участка кода → Сворачивать автоматически", ComfortSettings.PREF_FOLD_TEXT_LITERALS_INITIALLY, //$NON-NLS-1$
            "Большой текстовый литерал", Kind.FLAG), //$NON-NLS-1$
        new Setting("Редактор кода", ComfortSettings.PREF_SPELLING_CHECK_IDENTIFIERS_VISIBLE, //$NON-NLS-1$
            "Проверять орфографию в идентификаторах в видимой области", Kind.FLAG), //$NON-NLS-1$
        new Setting("Направляющие отступов", ComfortSettings.PREF_INDENT_GUIDE_ENABLED, //$NON-NLS-1$
            "Включить направляющие отступов текстовых редакторов", Kind.FLAG), //$NON-NLS-1$
        new Setting("Направляющие отступов", ComfortSettings.PREF_INDENT_GUIDE_LINE_ALPHA, //$NON-NLS-1$
            "Прозрачность", Kind.TEXT), //$NON-NLS-1$
        new Setting("Направляющие отступов", ComfortSettings.PREF_INDENT_GUIDE_LINE_STYLE, //$NON-NLS-1$
            "Стиль", Kind.STYLE), //$NON-NLS-1$
        new Setting("Направляющие отступов", ComfortSettings.PREF_INDENT_GUIDE_LINE_WIDTH, //$NON-NLS-1$
            "Толщина", Kind.TEXT), //$NON-NLS-1$
        new Setting("Направляющие отступов", ComfortSettings.PREF_INDENT_GUIDE_LINE_SHIFT, //$NON-NLS-1$
            "Сдвиг", Kind.TEXT), //$NON-NLS-1$
        new Setting("Направляющие отступов", ComfortSettings.PREF_INDENT_GUIDE_LINE_COLOR, //$NON-NLS-1$
            "Цвет", Kind.COLOR), //$NON-NLS-1$
        new Setting("Направляющие отступов", ComfortSettings.PREF_INDENT_GUIDE_LINE_COLOR_DARK, //$NON-NLS-1$
            "Цвет", Kind.COLOR), //$NON-NLS-1$
        new Setting("Направляющие отступов", ComfortSettings.PREF_INDENT_GUIDE_DRAW_LEAD_EDGE, //$NON-NLS-1$
            "Рисовать на первой колонке", Kind.FLAG), //$NON-NLS-1$
        new Setting("Направляющие отступов", ComfortSettings.PREF_INDENT_GUIDE_DRAW_BLANK_LINE, //$NON-NLS-1$
            "Рисовать на пустых строках", Kind.FLAG), //$NON-NLS-1$
        new Setting("Направляющие отступов", ComfortSettings.PREF_INDENT_GUIDE_DRAW_COMMENT_BLOCK, //$NON-NLS-1$
            "Рисовать в блочных комментариях /* */", Kind.FLAG), //$NON-NLS-1$
        new Setting("Направляющие отступов", ComfortSettings.PREF_INDENT_GUIDE_CONTENT_TYPES, //$NON-NLS-1$
            "Типы файлов", Kind.EXCLUDED_TYPES), //$NON-NLS-1$
        new Setting("Другие настройки", ComfortSettings.PREF_ENABLE_HOVER_HINTS, //$NON-NLS-1$
            "Подсказки при наведении без Ctrl", Kind.FLAG), //$NON-NLS-1$
        new Setting("Другие настройки", ComfortSettings.PREF_SPELLING_IGNORE_CAMEL_CASE_ABBREVIATIONS, //$NON-NLS-1$
            "Игнорировать сокращения в CamelCase", Kind.FLAG) //$NON-NLS-1$
    );

    private ComfortVisibleSettings() {}

    static String label(String key)
    {
        return SETTINGS.stream().filter(setting -> setting.key().equals(key))
            .findFirst().orElseThrow(() -> new IllegalArgumentException(key)).label();
    }

    static String[] indentGuideStyles()
    {
        return INDENT_GUIDE_STYLES.clone();
    }

    /** Сохранённые значения, включая выключенные флажки и значения по умолчанию. */
    static String reportText()
    {
        ContentAssistSettings settings = ContentAssistSettings.getInstance();
        IPreferenceStore store = settings != null ? settings.getPreferenceStore()
            : ComfortSettings.getInstance() != null ? ComfortSettings.getInstance().getPreferenceStore() : null;
        if (store == null)
            return "— (хранилище настроек недоступно)"; //$NON-NLS-1$

        StringBuilder result = new StringBuilder();
        String section = null;
        for (Setting setting : SETTINGS)
        {
            if (ComfortSettings.PREF_SPELLING_CHECK_IDENTIFIERS_VISIBLE.equals(setting.key())
                && !ComfortJdtAvailability.isJdtUiAvailable())
                continue;
            if (!setting.section().equals(section))
            {
                if (section != null)
                    result.append('\n');
                section = setting.section();
                result.append(section).append(":\n"); //$NON-NLS-1$
            }
            String label = setting.label().replaceFirst(":$", ""); //$NON-NLS-1$ //$NON-NLS-2$
            if (setting.kind() == Kind.FLAG)
                result.append(store.getBoolean(setting.key()) ? "[x] " : "[ ] ").append(label); //$NON-NLS-1$ //$NON-NLS-2$
            else
            {
                if (ComfortSettings.PREF_INDENT_GUIDE_LINE_COLOR.equals(setting.key()))
                    label += " (светлая тема)"; //$NON-NLS-1$
                else if (ComfortSettings.PREF_INDENT_GUIDE_LINE_COLOR_DARK.equals(setting.key()))
                    label += " (тёмная тема)"; //$NON-NLS-1$
                else if (setting.kind() == Kind.EXCLUDED_TYPES)
                    label += " (исключённые)"; //$NON-NLS-1$
                result.append(label).append(" = ").append(oneLine(value(store, setting))); //$NON-NLS-1$
            }
            result.append('\n');
        }
        return result.toString();
    }

    private static String value(IPreferenceStore store, Setting setting)
    {
        return switch (setting.kind())
        {
            case THEME_COLOR -> {
                RGB stored = PreferenceConverter.getColor(store, setting.key());
                yield rgb(ThemeAwareColors.toEffectiveRgb(stored)) + " (RGB текущей темы; сохранено: " //$NON-NLS-1$
                    + rgb(stored) + ")"; //$NON-NLS-1$
            }
            case COLOR -> rgb(PreferenceConverter.getColor(store, setting.key())) + " (RGB)"; //$NON-NLS-1$
            case STYLE -> {
                int style = store.getInt(setting.key());
                yield style >= 1 && style <= INDENT_GUIDE_STYLES.length
                    ? INDENT_GUIDE_STYLES[style - 1] : Integer.toString(style);
            }
            case EXCLUDED_TYPES -> {
                String excluded = store.getString(setting.key());
                yield excluded.isEmpty() ? "нет" : Arrays.stream(excluded.split("\\|")) //$NON-NLS-1$ //$NON-NLS-2$
                    .sorted().map(ComfortVisibleSettings::contentTypeLabel).collect(Collectors.joining(", ")); //$NON-NLS-1$
            }
            default -> store.getString(setting.key());
        };
    }

    private static String contentTypeLabel(String id)
    {
        IContentType type = Platform.getContentTypeManager().getContentType(id);
        return type == null ? id : IndentGuidePreferencePage.contentTypeLabel(type) + " (" + id + ")"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String rgb(RGB color)
    {
        return color.red + "," + color.green + "," + color.blue; //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static String oneLine(String value)
    {
        return value.isEmpty() ? "\"\"" : value.replace("\\", "\\\\") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            .replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$ //$NON-NLS-6$
    }
}
