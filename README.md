# ColorMindParty

小型独立 Paper 26.2 色盲派对插件。主逻辑集中在一个 Java 文件：

```text
src/main/java/com/verdae/colormindparty/ColorMindPartyPlugin.java
```

## 构建环境

- JDK 25 或更高版本
- Maven 3.9+
- Paper 26.2 experimental / alpha API

`pom.xml` 当前使用：

```xml
<paper.version>26.2.build.40-alpha</paper.version>
```

如果 Maven 提示找不到该依赖，请打开 Paper Javadocs 或 Paper 下载页查看最新 26.2 build，并替换 `pom.xml` 中的 `paper.version`。

## 构建

```bash
mvn clean package
```

生成文件：

```text
target/ColorMindParty-1.0.0.jar
```

把该 jar 放到 Paper 服务端的 `plugins/` 目录，然后重启服务器。

## 管理配置流程

```text
/cm set survival
/cm create <地图名称>
/cm setblock start
/cm setblock end
/cm setlobby
/cm setspawn
/cm set deathspawn
/cm save <地图名称>
/cm quit
/cm edit <地图名称>
/cm register <地图名称>
/cm unregister <地图名称>
```

说明：

- `/cm set survival`：在当前位置设置生存主世界返回点。
- `/cm create <地图名称>`：创建独立世界并传送管理员进入编辑。
- `/cm setblock start` 与 `/cm setblock end`：设置单层动态地板两角，长宽必须为单数。
- `/cm setlobby`：设置等待大厅。
- `/cm setspawn`：设置开局出生点。
- `/cm set deathspawn`：设置死亡后旁观者复活点。
- `/cm save <地图名称>`：保存地图配置。
- `/cm edit <地图名称>`：进入对应地图编辑。
- `/cm register <地图名称>`：注册为可加入地图。
- `/cm unregister <地图名称>`：取消可用状态。

## 玩家流程

```text
/cm join <地图名称>
/cm quit
```

## 强制管理

```text
/cmforce start [地图名称]
/cmforce stop [地图名称]
```

- `/cmforce start`：如果管理员在某个地图/lobby/编辑世界内，可省略地图名。
- `/cmforce stop`：强行终止游戏，所有相关玩家回生存主世界，清空背包，切回生存模式。

## 权限

```text
colormindparty.admin
```

默认 OP 拥有。

## 已实现功能概览

- 独立地图世界创建、编辑、保存、注册/取消注册
- 生存主世界返回点
- lobby 加入、退出、冒险/生存模式切换、清空背包
- 人数大于等于配置人数后自动 30 秒倒计时
- 最后 10 秒大标题和音效
- 开局预设地板填充
- 颜色方块提示、状态栏倒计时、音符盒音效
- 第 1-10 回合压缩自由跑动时间
- 第 11 回合开始启用 PVP
- 第 12-20 回合压缩看色倒计时时间，最低 1 秒
- 玩家掉落/死亡淘汰，旁观者传送到 deathspawn
- 最后一名玩家 5 秒胜负判定
- 无人幸存时聊天栏显示前三名
- 右侧计分板：色盲派对、第 X 回合、幸存 X、XX 色
- 颜色保护道具：新回合概率获得，右键将自身周围 3x3 替换为目标颜色
- 六种地板布局：杂色、条带、圆圈、面积、斜线、固定四角
- 六类方块池：陶瓦、木板、混凝土、羊毛、玻璃、带釉陶瓦
- 游戏中/恢复中禁止加入
- 中途离线判负，重进后会第一时间 kill

## 配置

初始 `config.yml`：

```yaml
settings:
  min-players: 5
  countdown-seconds: 30
  protection-chance: 0.18
  recovery-seconds: 3
survival: {}
arenas: {}
```

建议优先使用 `/cm` 命令写入坐标，不要手动编辑坐标。
