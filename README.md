<p align="center">
  <img src="xtranslator_logo.jpg" alt="XTranslator Logo" width="180"/>
</p>

# XTranslator

Mod tự động dịch thuật thời gian thực cho Minecraft NeoForge 1.21. Can thiệp trực tiếp vào Font Engine và Language System, hiển thị bản dịch tiếng Việt ngay trên giao diện mà không cần reload (`F3 + T`).

---

## Tính năng chính

- **Dịch trực tiếp trên màn hình**: Nhận diện và dịch GUI, bảng kỹ năng (Tensura, Iron's Spells...), FTB Quests, sách hướng dẫn, tooltip tức thì khi mở menu hoặc rê chuột.
- **Bảo vệ định dạng (`FormatProtector`)**: Giữ nguyên toàn bộ mã màu (`§a`, `§c`...) và biến định dạng Java (`%s`, `%d`, `{0}`).
- **Phím tắt nhanh**: Nhấn **V** khi đang mở bất kỳ giao diện nào để quét và dịch toàn bộ nội dung của màn hình đó.
- **Động cơ kép**: Tự động chuyển đổi giữa Google Translate và MyMemory API khi bị giới hạn tần suất (rate-limit).
- **Chế độ On-Demand & Full**:
  - `ON_DEMAND` *(Mặc định)*: Chỉ dịch những gì hiển thị trên màn hình, tối ưu hiệu năng, không giật lag.
  - `FULL`: Quét và dịch toàn bộ chuỗi ngôn ngữ của modpack vào Resource Pack.
- **Quản lý in-game**: Chỉnh sửa trực tiếp bản dịch (`/xtrans edit`) và quản lý bộ nhớ đệm cache trong game.

---

## Phím tắt và Câu lệnh

Tiền tố hỗ trợ: `/xtrans` hoặc `/xtranslator`.

| Lệnh / Phím tắt | Chức năng | Ví dụ |
| :--- | :--- | :--- |
| **Phím V** | Quét và dịch toàn bộ giao diện đang mở | Nhấn **V** trong menu kỹ năng |
| `/xtrans screen` | Tương đương phím V | `/xtrans screen` |
| `/xtrans mod <tên_mod>` | Dịch toàn bộ chuỗi của 1 mod (hỗ trợ Tab) | `/xtrans mod tensura` |
| `/xtrans match <từ_khóa>` | Dịch các mod có tên chứa từ khóa | `/xtrans match create` |
| `/xtrans full` | Quét và dịch toàn bộ các mod trong máy | `/xtrans full` |
| `/xtrans list [lọc]` | Danh sách mod chưa có tiếng Việt | `/xtrans list` |
| `/xtrans edit <key> <text>` | Sửa nhanh bản dịch của một khóa | `/xtrans edit item.tensura.core Lõi` |
| `/xtrans status` | Xem trạng thái và tiến trình dịch | `/xtrans status` |
| `/xtrans cancel` | Hủy tiến trình dịch hiện tại | `/xtrans cancel` |
| `/xtrans clear [modid/all]` | Xóa cache bản dịch | `/xtrans clear tensura` |
| `/xtrans help` | Xem hướng dẫn câu lệnh | `/xtrans help` |

---

## Cấu hình

File cấu hình: `config/xtranslator-client.toml`

```toml
[client]
    enabled = true                  # Bật/tắt tự động dịch
    autoActivateResourcePack = true # Tự kích hoạt Resource Pack
    translationDelayMs = 0          # Độ trễ giữa các lượt dịch (ms)
    sourceLanguage = "auto"         # Ngôn ngữ nguồn (mặc định: tự nhận diện)
    targetLanguage = "auto"         # Ngôn ngữ đích (mặc định: theo ngôn ngữ Minecraft)
    mode = "ON_DEMAND"              # Chế độ: 'ON_DEMAND' hoặc 'FULL'
```

---

## Cài đặt

1. Yêu cầu **Minecraft 1.21** chạy **NeoForge** (khuyến nghị `21.0.167` trở lên).
2. Đặt file `xtranslator-1.0.0.jar` vào thư mục `.minecraft/mods/`.
3. Vào game, chọn ngôn ngữ **Tiếng Việt** trong cài đặt Minecraft.
4. Bản dịch được tự động lưu vào Resource Pack: `.minecraft/resourcepacks/XTranslator-Pack/`.

### Biên dịch từ mã nguồn (JDK 21)

```bash
git clone https://github.com/tmajh25/X-Translator.git
cd X-Translator
./gradlew build
```

File `.jar` sau khi build nằm tại `build/libs/`.

---

## Giấy phép

Phát hành theo giấy phép [MIT License](LICENSE). Dựa trên ý tưởng ban đầu của AutoTranslator (Pocky-l).
