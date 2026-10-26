import fs from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { Presentation, PresentationFile } from '@oai/artifact-tool';

// 使用工作区绑定的Node与包目录，在.artifacts/ppt-build运行该模块副本。
const root = process.cwd();
process.env.RUNTIME_NODE_MODULES ??= 'C:/Users/123/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules';
const build = path.join(root, '.artifacts/ppt-build');
const skill = 'C:/Users/123/.codex/plugins/cache/openai-primary-runtime/presentations/26.1025.11240/skills/presentations';
const { finalizePresentation, applyPresentationChartFont } = await import(pathToFileURL(path.join(skill, 'container_tools/artifact_tool_utils.mjs')).href);
const font = 'Microsoft YaHei';
const green = '#195C4A', ink = '#15352D', muted = '#52665E', paper = '#F8F7F1';
const presentation = Presentation.create({ slideSize: { width: 1280, height: 720 } });

function text(slide, value, x, y, width, height, size=28, color=ink, bold=false) {
  const shape = slide.shapes.add({ geometry: 'textbox', position: { left:x, top:y, width, height }, fill:'none', line:{fill:'none',width:0} });
  shape.text = value;
  shape.text.style = { typeface:font, fontSize:size, color, bold };
  shape.text.insets = 0;
  return shape;
}
function slide(title, source) {
  const s = presentation.slides.add(); s.background.fill = paper;
  text(s, title, 64, 46, 1150, 70, 44, ink, true);
  text(s, `阅集图书商城   本地验收预览                          ${String(presentation.slides.items.length).padStart(2,'0')}`, 64, 674, 1140, 30, 18, muted);
  s.speakerNotes.text = `来源：${source}。数据截至2026-10-26，LOCAL_MOCK与真实沙箱结果分开；成员署名及教师格式待补。`;
  return s;
}
function lines(s, items, x=64, y=160, w=1120, step=88, size=30) {
  items.forEach((v,i)=>text(s,v,x,y+i*step,w,step-12,size));
}
function node(s, label, x,y,w=260,h=76) {
  const box=s.shapes.add({geometry:'rect',position:{left:x,top:y,width:w,height:h},fill:'#FFFFFF',line:{fill:green,width:1.5}});
  text(s,label,x+16,y+18,w-32,h-24,25,ink,true); return box;
}
function edge(s,x,y,w,h=0) {
  s.shapes.add({geometry:'line',position:{left:x,top:y,width:w,height:h},line:{fill:muted,width:2}});
}

let s=slide('阅集图书商城','docs/01-总需求文档.md；docs/stages/evidence/R-validation.json');
text(s,'多商家实体新书商城',64,178,1100,85,56,green,true);
lines(s,['读者购买与售后，书店库存与履约，平台监管与财务','本地业务闭环通过，外部与持续运行验收仍有待办'],64,310,1120,90,31);
text(s,'Java 21    Spring Boot 3.5    Vue 3',64,542,1100,56,30,muted);

s=slide('业务范围与权限','docs/01-总需求文档.md 第4—6节');
lines(s,['会员：本人地址、购物车、订单、售后与评价','商家和仓库：由服务端确认本店范围','客服和财务：审核、退款、对账与职责分离结算','管理员：维护授权；商家出款始终模拟'],64,158,1120,96,31);
text(s,'首版包邮，一商品一SKU，每店独立订单和支付',64,580,1120,46,27,green,true);

s=slide('模块化单体架构','docs/02-系统设计文档.md；infra/compose.yml');
node(s,'Vue 3 浏览器',64,164,260); node(s,'Nginx 反向代理',436,164,310); node(s,'Spring Boot 单体',868,164,332);
edge(s,324,202,112); edge(s,746,202,122);
node(s,'MySQL 交易事实',64,374,310); node(s,'Redis 展示与辅助',452,374,320); node(s,'RabbitMQ 事件',850,374,350);
edge(s,1034,240,0,76); edge(s,219,316,815); edge(s,219,316,0,58); edge(s,612,316,0,58); edge(s,1025,316,0,58);
text(s,'Controller / Service / Repository 分层；事务由服务控制',64,516,1120,52,30);
text(s,'支付宝沙箱适配、持久文件卷和监控作为明确边界',64,586,1120,50,28,muted);

s=slide('交易数据关系与快照','docs/03-数据库设计.md；V1—V11迁移');
node(s,'Book 公共书目',64,162,300); node(s,'Product / SKU 店铺商品',464,162,470); edge(s,364,200,100);
node(s,'结算批次',64,306,300); node(s,'店铺订单与订单行',464,306,470); edge(s,364,344,100);
node(s,'支付与退款',64,450,300); node(s,'结算明细与调整',464,450,470); edge(s,364,488,100);
lines(s,['订单保存价格、商品与地址快照','ID与金额接口统一为字符串','退款、物流、结算状态分别保存'],970,174,240,140,25);
text(s,'V11追加事件消费去重；字段和约束以03及迁移为准',64,588,1120,55,28,green);

s=slide('库存与支付一致性','docs/02第3—5节；R-process-crash.json；StageD/StageE测试');
text(s,'下单与库存',64,155,500,50,34,green,true);
lines(s,['同事务创建全部店铺订单','按SKU确定顺序加锁和预占','同键重放返回原业务结果'],64,238,515,95,30);
text(s,'渠道与补偿',678,155,520,50,34,green,true);
lines(s,['每订单一个paymentNo','渠道调用在数据库事务外','关闭未知保留预占并重查'],678,238,520,95,30);
text(s,'实际SIGKILL后按原号恢复，支付仅一笔、库存仅扣一次',64,590,1120,54,29,ink,true);

s=slide('售后退款与模拟结算','StageFHttpTests；FinancialBoundaryHttpTests；R-settlement-mock-paid.png');
lines(s,['未发货退款成功才回补','可再售退货验收时回补','两条路径各自只能一次','逐单手续费快照，创建审核分离'],64,158,500,97,28);
s.images.add({blob:new Uint8Array(await fs.readFile(path.join(root,'.artifacts/R-settlement-mock-paid.png'))),contentType:'image/png',alt:'真实浏览器模拟结算已付款记录',fit:'contain',position:{left:615,top:158,width:600,height:425}});
text(s,'截图为LOCAL_MOCK联调数据，商家出款始终模拟',615,599,600,45,23,muted);

s=slide('身份隔离与已知风险','docs/02第7节；SessionBoundaryTests；ADR-19；镜像扫描');
lines(s,['访问JWT 15分钟，刷新7天轮换与重放撤销','权限变更、退出与禁用及时失效；关键写强校验','本人和本店归属检查，CSRF及私有文件下载鉴权','17项镜像高危及严重发现仍在，用户接受本地风险'],64,160,1120,103,30);
text(s,'保留当前技术版本，不生产发布，扫描失败如实留档',64,590,1120,50,29,green,true);

s=slide('实际质量验证','docs/stages/evidence/R-validation.json；R-junit-sanitized.xml');
text(s,'服务分支覆盖率 %',64,153,570,50,29,green,true);
const chart=s.charts.add('bar',{position:{left:64,top:224,width:570,height:350},categories:['历史H','当前R'],series:[{name:'服务分支覆盖率',values:[72.51,80.1777],fill:green}],hasLegend:false,barOptions:{direction:'column'},dataLabels:{showValue:true,position:'outEnd',textStyle:{typeface:font,fontSize:24}},yAxis:{min:0,max:100,textStyle:{fontSize:24}},xAxis:{textStyle:{fontSize:24}}});
applyPresentationChartFont(chart,{fontFamily:font});
lines(s,['178项后端测试通过','10项前端单元测试通过','5项桌面手机E2E通过','92项接口成功和拒绝响应观测'],705,177,510,96,29);
text(s,'固定门槛80%已通过；正式压测和24小时结果尚未取得',64,607,1140,46,26,muted);

s=slide('部署监控与恢复证据','R-local-recovery.json；R-local-rollback.json；R-alerts-observed.jsonl；GitHub run40282687327');
lines(s,['容器按digest运行，日志和业务指标可查','强杀触发不可用告警，恢复通知已实际收到','隔离恢复7订单、6支付、4退款、1结算及文件','前端digest回退通过，数据库保持V11'],64,161,1120,101,30);
text(s,'源码安全配置已修复，依赖风险留档；未执行部署',64,590,1120,50,28,green,true);

s=slide('剩余验收与AI留痕','docs/14-剩余任务与验收计划.md；R-AI使用记录.md；R-alipay-connectivity.json');
lines(s,['沙箱只读查单成功，等待可登录买家完成交易','独立备份、固定口径压测和24小时窗口尚未完成','需求、实现、契约、测试与Git提交建立对应','补教师模板、成员分工、原始对话与本人签名'],64,156,1120,104,30);
text(s,'本地闭环已验证，课程完整验收仍按实际条件推进',64,596,1120,51,28,green,true);

await fs.mkdir(build,{recursive:true});
for (let i=0;i<presentation.slides.items.length;i++) {
  const png=await presentation.export({slide:presentation.slides.items[i],format:'png',scale:1});
  await fs.writeFile(path.join(build,`slide-${i+1}.png`),new Uint8Array(await png.arrayBuffer()));
}
const candidatePath=path.join(build,'candidate.pptx');
await (await PresentationFile.exportPptx(presentation)).save(candidatePath);
await finalizePresentation({
  workspaceDir:root,candidatePath,finalPath:path.join(root,'deliverables/04-阅集商城项目汇报.pptx'),
  pythonExecutable:'C:/Users/123/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe',
  integrityValidatorPath:path.join(skill,'container_tools/inspect_presentation_package_integrity.py'),
  layoutValidatorPath:path.join(skill,'container_tools/inspect_presentation_layout_geometry.py'),
  layoutArgs:['--expected-slide-size-emu','12192000,6858000','--validate-bullet-geometry','--validate-heading-fit'],
  requiredNativeChartOwnerSlides:[8],materializeLiteralChartWorkbooks:true,fontPolicy:{basis:'design',families:[font]},
  verifyArtifactToolImport:true,receiptPath:path.join(build,`validation-${Date.now()}.json`),
});
console.log('10 editable slides exported and finalized');
