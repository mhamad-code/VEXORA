# Claude Code السحابي (GitHub Codespaces)

قسم **Claude Code** يشغّل Claude Code داخل Codespace يملكه المستخدم على حسابه في GitHub، ويعرض أحداثه ببطاقات التطبيق نفسها. وكيل التطبيق الداخلي وأدواته **لا يشاركون** في هذه المحادثة: كل العمل (تنفيذ الأوامر، تعديل الملفات، استدعاء النماذج) يحدث في السحابة.

## البنية

```
التطبيق (Android)
 ├─ CloudGitHubAuth        تسجيل دخول GitHub عبر OAuth Device Flow (بدون client secret)
 ├─ CodespacesApi          REST: المستودع، الملفات، Codespace، المنافذ
 ├─ CloudSetup             آلة حالات الإعداد (خطوات قابلة لإعادة المحاولة)
 ├─ CloudGateway           توليد إعداد LiteLLM من مزودي التطبيق
 └─ CloudRelayClient       WebSocket مع تسلسل وإعادة اتصال
        │  wss://<codespace>-8765.app.github.dev/relay
        ▼
   Codespace (المستخدم)
     ├─ relay.mjs           يستقبل أوامر محددة فقط، يرقّم الأحداث، يعيد تشغيلها
     ├─ LiteLLM proxy       127.0.0.1:4000  (Anthropic Messages ⇄ OpenAI/Gemini)
     └─ claude -p --input-format stream-json --output-format stream-json --verbose
```

### لماذا LiteLLM؟

Claude Code يتحدث بصيغة Anthropic Messages، ومزودو التطبيق معظمهم بصيغة OpenAI. LiteLLM هو الوسيط الموثّق الذي يقدّم `/v1/messages` ويترجم إلى مزودي OpenAI/Gemini، مع `use_chat_completions_url_for_anthropic_messages: true` حتى تُوجَّه الطلبات إلى `/chat/completions` لا `/responses`. البديل `claude-code-router` غير مدعوم رسمياً من Anthropic، لذلك لم يُستخدم.

### النماذج

- يختار المستخدم **النموذج الرئيسي** و**النموذج السريع** من مزودي التطبيق فقط.
- تُستبعد كل مزودات/نماذج Anthropic من القائمة، ولا يوجد تسجيل دخول بحساب Anthropic.
- تُسمّى النماذج داخل الوسيط بأسماء مستعارة ثابتة (`vexora-main`, `vexora-fast`) حتى لا تتأثر أسماء Claude Code الداخلية بأسماء المزود.
- يُضبط `ANTHROPIC_MODEL` و`ANTHROPIC_DEFAULT_HAIKU_MODEL` و`CLAUDE_CODE_SUBAGENT_MODEL`، وتُسجَّل نافذة السياق في `model_info.max_input_tokens` لكل نموذج.
- زر **اختبار النموذج** يرسل طلباً صغيراً بأداة واحدة عبر الـ relay، ويحذّر إذا لم يستدعِ النموذج الأداة.

## الإعداد

### 1) تسجيل GitHub OAuth App

1. GitHub → Settings → Developer settings → **OAuth Apps** → New OAuth App.
2. أي اسم، وHomepage URL يمكن أن يكون رابط المستودع.
3. فعّل **Enable Device Flow**.
4. لا تحتاج Client Secret إطلاقاً؛ التطبيق لا يستخدمه.
5. ضع الـ Client ID في `local.properties` (ملف متجاهل في Git):
   ```properties
   GITHUB_CLIENT_ID=Iv1xxxxxxxxxxxx
   ```
   أو اتركه فارغاً وأدخله من داخل التطبيق (إعدادات قسم السحابة → Client ID). الصلاحيات المطلوبة عند الدخول: `codespace` و`repo` (لإنشاء المستودع الخاص فقط).

### 2) مزودو النماذج

أضف مزوداً واحداً على الأقل بصيغة OpenAI-compatible مع مفتاح صالح في **Settings → Providers**. تُستخدم مفاتيح المزودين الموجودة؛ لا يُطلب مفتاح جديد لهذا القسم.

### 3) الإعداد بضغطة واحدة

زر **إعداد السحابة** يعرض أولاً بطاقة موافقة توضّح: مستودع خاص باسم `cloud-workspace` (قابل للتعديل) وCodespace على حسابك، وأن الاستهلاك يُحسب من حصة Codespaces. بعد الموافقة:

1. إنشاء المستودع الخاص (أو استخدامه إن وُجد، ويُرفض المستودع العام).
2. رفع `.devcontainer/devcontainer.json`، `.cloud-claude/setup.sh`، `start-relay.sh`، `relay.mjs`، `README.md` و`relay-token.sha256`.
3. إنشاء Codespace بأصغر جهاز مناسب و`idle_timeout_minutes` قابل للضبط.
4. انتظار `Available`.
5. فتح المنفذ 8765 وانتظار `/health`.

كل خطوة تُعرض في Stepper، والفشل يوقف عند الخطوة ويسمح بإعادة المحاولة منها فقط (بدون استهلاك حصة جديدة).

## الأمان

- **مستودع خاص فقط**؛ رفض أي مستودع عام بنفس الاسم.
- **لا أسرار في المستودع**: يُولَّد سرّ عشوائي 256-bit على الجهاز، ويُرفع **hash** فقط (`SHA-256`). السر نفسه يبقى في تخزين التطبيق المشفّر (Android Keystore).
- **مفاتيح المزودين** تُرسل بعد مصادقة WebSocket فقط، وتُكتب في `~/.cloud-claude/gateway.env` بصلاحيات `0600` خارج المستودع.
- **سطح أوامر مغلق**: الـ relay يقبل `hello, start, message, interrupt, permission, resume, status, stop` فقط، ويغلق أي رسالة أخرى، ويحدّ المعدل (40 رسالة/5 ثوان لكل اتصال).
- **مقايضة المنفذ**: GitHub لا يوثّق REST لتغيير ظهور المنافذ؛ التطبيق يجرّب نقطة النهاية التي يستخدمها `gh`، ويشرح البديل اليدوي عند الرفض. المنفذ **عام عبر HTTPS** (TLS من GitHub) لكنه محمي بالسرّ + تحديد المعدل. البديل (منفذ خاص) يحتاج مصادقة متصفح غير متاحة لتطبيق موبايل.
- **الأذونات**: افتراضياً «اسأل دائماً»؛ «تجاوز كامل» يطلب تأكيداً صريحاً. الأحداث المعروضة تُمرَّر عبر إخفاء الأسرار الموجود في التطبيق عند عرضها في السجلات.

## إعادة الاتصال والاستئناف

- كل حدث يحمل رقماً تسلسلياً.
- عند انقطاع الشبكة أو قفل الهاتف تستمر المهمة في السحابة، ويعيد التطبيق الاتصال بـ `hello {since: lastSeq}` فيعيد الـ relay كل ما فات فقط.
- «إيقاف المهمة» يرسل `interrupt` (SIGINT) ويحفظ معرّف الجلسة لاستئنافها بـ `--resume`.

## حدود النسخة الأولى

- الأذونات التفاعلية في الوضع headless تعتمد على إصدار Claude Code: إن أرسل `control_request` ظهرت بطاقة موافقة، وإلا يُنصح باستخدام «قبول التعديلات» أو تجاوز مصرّح به.
- لا وكلاء غير Claude، لا طرفية حقيقية، لا SSH، لا مزامنة ملفات للهاتف، لا ACP — لكن `CloudAgent`/`CloudProvider` واجهتان جاهزتان لإضافتها.
- نقطة نهاية ظهور المنفذ غير موثّقة رسمياً؛ عند فشلها يظهر للمستخدم أمر `gh codespace ports visibility 8765:public`.

## نقاط مراجعة قبل النشر للآخرين

- راجع شروط ترخيص Claude Code بخصوص تثبيته واستخدامه مع نماذج غير Claude (توجيه عبر Gateway).
- راجع سياسة GitHub بخصوص Codespaces والاستخدام الآلي.
