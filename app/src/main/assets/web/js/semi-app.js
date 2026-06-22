const switchMode = () => {
  const body = document.body;
  if (body.hasAttribute('theme-mode')) {
    body.removeAttribute('theme-mode');
    window.setMode('light');
  } else {
    body.setAttribute('theme-mode', 'dark');
    window.setMode('dark');
  }
};
const {
  useState,
  useEffect,
  useMemo,
  useRef,
  useCallback
} = React;
const {
  Collapse,
  Switch,
  Input,
  RadioGroup,
  Radio,
  Button,
  Toast,
  SideSheet,
  Checkbox,
  InputNumber,
  Typography
} = SemiUI;
const {
  IconSearch
} = SemiIcons;
const {
  Title,
  Text
} = Typography;

// --- Mock Data Wrappers ---
const getTabsData = () => {
  try {
    return JSON.parse(window.HOOK.getTabs());
  } catch (e) {
    return [];
  }
};
const getModelData = code => {
  try {
    return JSON.parse(window.HOOK.getModel(code));
  } catch (e) {
    return [];
  }
};
const getFieldData = (modelCode, fieldCode) => {
  try {
    const res = JSON.parse(window.HOOK.getField(modelCode, fieldCode));
    return res.expandValue || [];
  } catch (e) {
    return [];
  }
};

// --- Main Component ---
const App = () => {
  // 1. 处理主题自动切换逻辑
  const applyTheme = () => {
    let isDark = false;
    if (window.HOOK && window.HOOK.isNightMode) {
      try {
        isDark = window.HOOK.isNightMode();
      } catch (e) {}
    } else {
      if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) isDark = true;
    }
    const body = document.body;
    if (isDark) {
      body.setAttribute('theme-mode', 'dark');
      if (window.setMode) window.setMode('dark');
    } else {
      if (body.hasAttribute('theme-mode')) body.removeAttribute('theme-mode');
      if (window.setMode) window.setMode('light');
    }
  };
  applyTheme();
  const [tabs, setTabs] = useState([]);
  const [modelsMap, setModelsMap] = useState({});

  // 列表弹窗相关的 State
  const [sheetVisible, setSheetVisible] = useState(false);
  const [sheetConfig, setSheetConfig] = useState({
    modelCode: '',
    fieldCode: '',
    type: '',
    title: '',
    desc: ''
  });
  const [sheetList, setSheetList] = useState([]); // 完整列表数据
  const [sheetSearch, setSheetSearch] = useState(''); // 搜索关键词
  const [selectedIds, setSelectedIds] = useState(new Set()); // 选中的ID集合
  const [selectedCounts, setSelectedCounts] = useState({}); // ID对应的数值

  // 搜索防抖: 300ms延迟减少重渲染
  const searchTimer = useRef(null);
  const handleSearch = value => {
    if (searchTimer.current) clearTimeout(searchTimer.current);
    searchTimer.current = setTimeout(() => setSheetSearch(value), 300);
  };

  // 初始化
  useEffect(() => {
    const tabsData = getTabsData();
    setTabs(tabsData);
    const initialMap = {};
    tabsData.forEach(tab => {
      initialMap[tab.modelCode] = getModelData(tab.modelCode);
    });
    setModelsMap(initialMap);
  }, []);
  const handleFieldChange = (modelCode, fieldCode, newValue) => {
    setModelsMap(prev => {
      const currentFields = [...prev[modelCode]];
      const fieldIndex = currentFields.findIndex(f => f.code === fieldCode);
      if (fieldIndex > -1) {
        currentFields[fieldIndex] = {
          ...currentFields[fieldIndex],
          configValue: String(newValue)
        };
      }
      const savePayload = {};
      currentFields.forEach(f => {
        savePayload[f.code] = {
          configValue: f.configValue
        };
      });
      if (window.HOOK && window.HOOK.setModel) {
        window.HOOK.setModel(modelCode, JSON.stringify(savePayload));
      }
      return {
        ...prev,
        [modelCode]: currentFields
      };
    });
  };

  // --- 列表弹窗逻辑 (关键修改部分) ---

  const openListSheet = (modelCode, fieldItem) => {
    const {
      code,
      name,
      type,
      desc,
      configValue
    } = fieldItem;

    // 1. 先获取原始数据
    let listData = getFieldData(modelCode, code);

    // 2. 解析当前已选中的 ID，用于排序
    const newSelectedIds = new Set();
    const newSelectedCounts = {};
    try {
      const parsed = JSON.parse(configValue);
      if (type === 'SELECT') {
        if (Array.isArray(parsed)) parsed.forEach(id => newSelectedIds.add(id));
      } else if (type === 'SELECT_AND_COUNT') {
        if (parsed && typeof parsed === 'object') {
          Object.entries(parsed).forEach(([id, count]) => {
            newSelectedIds.add(id);
            newSelectedCounts[id] = count;
          });
        }
      }
    } catch (e) {
      console.error("Parse config value failed", e);
    }

    // 3. 执行排序：已选中的排在最前面 (只执行一次)
    // sort 会改变原数组，这里直接操作 listData 即可
    listData.sort((a, b) => {
      const isA = newSelectedIds.has(a.id);
      const isB = newSelectedIds.has(b.id);
      if (isA && !isB) return -1; // a 在前
      if (!isA && isB) return 1; // b 在前
      return 0; // 保持原顺序
    });

    // 4. 将排好序的数据设置到 State
    setSheetList(listData);
    setSheetConfig({
      modelCode,
      fieldCode: code,
      type,
      title: name,
      desc: desc
    });
    setSheetSearch('');
    setSelectedIds(newSelectedIds);
    setSelectedCounts(newSelectedCounts);
    setSheetVisible(true);
  };
  const saveSheet = () => {
    const {
      modelCode,
      fieldCode,
      type
    } = sheetConfig;
    let newValueStr = "";
    if (type === 'SELECT') {
      const arr = Array.from(selectedIds);
      newValueStr = JSON.stringify(arr);
    } else {
      const obj = {};
      selectedIds.forEach(id => {
        obj[id] = selectedCounts[id] !== undefined ? selectedCounts[id] : 0;
      });
      newValueStr = JSON.stringify(obj);
    }
    handleFieldChange(modelCode, fieldCode, newValueStr);
    setSheetVisible(false);
    Toast.success("已保存列表设置");
  };
  const toggleItem = useCallback((id, checked) => {
    setSelectedIds(prev => {
      const newIds = new Set(prev);
      if (checked) newIds.add(id);else newIds.delete(id);
      return newIds;
    });
    if (checked && sheetConfig.type === 'SELECT_AND_COUNT' && selectedCounts[id] === undefined) {
      setSelectedCounts(prev => ({
        ...prev,
        [id]: 1
      }));
    }
  }, [sheetConfig.type, selectedCounts]);
  const handleCountChange = (id, val) => {
    setSelectedCounts(prev => ({
      ...prev,
      [id]: val
    }));
  };
  const toggleAll = () => {
    const filteredList = sheetList.filter(item => item.name.includes(sheetSearch));
    const allSelected = filteredList.every(item => selectedIds.has(item.id));
    const newIds = new Set(selectedIds);
    filteredList.forEach(item => {
      if (allSelected) {
        newIds.delete(item.id);
      } else {
        newIds.add(item.id);
        if (sheetConfig.type === 'SELECT_AND_COUNT' && selectedCounts[item.id] === undefined) {
          setSelectedCounts(prev => ({
            ...prev,
            [item.id]: 1
          }));
        }
      }
    });
    setSelectedIds(newIds);
  };

  // 渲染过滤后的列表（useMemo：仅在依赖变化时重算）
  const renderedSheetList = useMemo(() => {
    const filtered = sheetList.filter(item => item.name.toLowerCase().includes(sheetSearch.toLowerCase()));
    if (filtered.length === 0) return /*#__PURE__*/React.createElement("div", {
      style: {
        padding: 20,
        textAlign: 'center',
        color: '#999'
      }
    }, "\u65E0\u6570\u636E");
    return filtered.map(item => {
      const isChecked = selectedIds.has(item.id);
      return /*#__PURE__*/React.createElement("div", {
        key: item.id,
        className: "list-item"
      }, /*#__PURE__*/React.createElement("div", {
        className: "list-item-left",
        onClick: () => toggleItem(item.id, !isChecked)
      }, /*#__PURE__*/React.createElement(Checkbox, {
        checked: isChecked,
        style: {
          pointerEvents: 'none'
        }
      }), /*#__PURE__*/React.createElement("span", {
        className: "list-item-name"
      }, item.name)), sheetConfig.type === 'SELECT_AND_COUNT' && isChecked && /*#__PURE__*/React.createElement(InputNumber, {
        className: "list-count-input",
        size: "small",
        min: 0,
        value: selectedCounts[item.id] || 0,
        onChange: v => handleCountChange(item.id, v),
        placeholder: "\u6570\u91CF"
      }));
    });
  }, [sheetList, sheetSearch, selectedIds, selectedCounts, sheetConfig.type]);

  // --- 渲染主界面配置项 ---
  const renderFieldItem = (modelCode, item) => {
    const {
      type,
      name,
      code,
      configValue,
      desc,
      expandKey
    } = item;
    if (type === 'BOOLEAN') {
      return /*#__PURE__*/React.createElement("div", {
        className: "field-row",
        key: code
      }, /*#__PURE__*/React.createElement("div", {
        className: "field-label"
      }, name), /*#__PURE__*/React.createElement("div", {
        className: "field-control"
      }, /*#__PURE__*/React.createElement(Switch, {
        checked: configValue === 'true',
        onChange: v => handleFieldChange(modelCode, code, v)
      })));
    }
    if (['MULTIPLY_INTEGER', 'LIST', 'INTEGER', 'STRING', 'TEXT'].includes(type)) {
      return /*#__PURE__*/React.createElement("div", {
        className: "field-row",
        key: code
      }, /*#__PURE__*/React.createElement("div", {
        className: "field-label"
      }, name), /*#__PURE__*/React.createElement("div", {
        className: "field-control",
        style: {
          width: '50%'
        }
      }, /*#__PURE__*/React.createElement(Input, {
        value: configValue,
        onChange: v => handleFieldChange(modelCode, code, v),
        placeholder: "\u8BF7\u8F93\u5165..."
      })));
    }
    if (type === 'URL_TEXT') {
      return /*#__PURE__*/React.createElement("div", {
        className: "field-row",
        key: code
      }, /*#__PURE__*/React.createElement(Text, {
        link: {
          href: configValue
        }
      }, name));
    }
    if (type === 'CHOICE') {
      return /*#__PURE__*/React.createElement("div", {
        style: {
          padding: '12px 0',
          borderBottom: '1px solid var(--semi-color-border)'
        },
        key: code
      }, /*#__PURE__*/React.createElement("div", {
        style: {
          marginBottom: 8,
          fontWeight: 600,
          fontSize: 14,
          color: 'var(--semi-color-text-0)'
        }
      }, name), /*#__PURE__*/React.createElement(RadioGroup, {
        type: "button",
        value: Number(configValue),
        onChange: e => handleFieldChange(modelCode, code, e.target.value)
      }, expandKey && expandKey.map((label, idx) => /*#__PURE__*/React.createElement(Radio, {
        value: idx,
        key: idx
      }, label))));
    }
    if (['SELECT', 'SELECT_AND_COUNT'].includes(type)) {
      let count = 0;
      try {
        const parsed = JSON.parse(configValue);
        count = Array.isArray(parsed) ? parsed.length : Object.keys(parsed).length;
      } catch (e) {}
      return /*#__PURE__*/React.createElement("div", {
        className: "field-row",
        key: code
      }, /*#__PURE__*/React.createElement("div", {
        className: "field-label"
      }, name), /*#__PURE__*/React.createElement("div", {
        className: "field-control"
      }, /*#__PURE__*/React.createElement(Button, {
        theme: "solid",
        type: "secondary",
        onClick: () => openListSheet(modelCode, item)
      }, "\u5DF2\u9009 ", count, " \u9879")));
    }
    return null;
  };
  return /*#__PURE__*/React.createElement("div", {
    className: "container"
  }, /*#__PURE__*/React.createElement("div", {
    className: "header"
  }, /*#__PURE__*/React.createElement(Title, {
    heading: 2,
    style: {
      margin: 0
    }
  }, "Sesame-TK \u8BBE\u7F6E"), /*#__PURE__*/React.createElement(Button, {
    disabled: true,
    theme: "borderless"
  }, "\u6B22\u8FCE\u52A0\u5165\u6211\u4EEC\u7684\u7EC4\u7EC7"), /*#__PURE__*/React.createElement(Text, {
    style: {
      marginRight: 15
    },
    link: {
      href: 'https://t.me/fansirsqi_xposed_sesame'
    }
  }, " \u27A4TG "), " |", /*#__PURE__*/React.createElement(Text, {
    style: {
      marginLeft: 15,
      marginRight: 15
    },
    link: {
      href: 'https://qm.qq.com/q/740DFRGOXe'
    }
  }, " \u27A4QQ "), /*#__PURE__*/React.createElement(Button, {
    style: {},
    onClick: switchMode
  }, " \u5207\u6362\u4E3B\u9898 ")), /*#__PURE__*/React.createElement("div", {
    className: "section-padding"
  }, /*#__PURE__*/React.createElement(Collapse, {
    accordion: true,
    defaultActiveKey: tabs[0]?.modelCode
  }, tabs.map(tab => /*#__PURE__*/React.createElement(Collapse.Panel, {
    header: /*#__PURE__*/React.createElement("div", {
      style: {
        fontWeight: 600
      }
    }, tab.modelName),
    itemKey: tab.modelCode,
    key: tab.modelCode
  }, /*#__PURE__*/React.createElement("div", null, modelsMap[tab.modelCode] ? modelsMap[tab.modelCode].map(field => renderFieldItem(tab.modelCode, field)) : /*#__PURE__*/React.createElement("div", {
    style: {
      padding: 20,
      textAlign: 'center'
    }
  }, "\u52A0\u8F7D\u4E2D...")))))), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: 20,
      textAlign: 'center'
    }
  }, /*#__PURE__*/React.createElement(Button, {
    theme: "solid",
    type: "danger",
    block: true,
    onClick: () => {
      if (window.HOOK && window.HOOK.saveOnExit) {
        window.HOOK.saveOnExit();
      } else if (window.Android && window.Android.onExit) {
        window.Android.onExit();
      } else {
        console.log("模拟: 保存并退出");
      }
    }
  }, "\u9000\u51FA\u5E76\u4FDD\u5B58"), /*#__PURE__*/React.createElement(Text, {
    style: {
      marginTop: 15,
      color: '#999',
      fontSize: 12
    },
    link: {
      href: 'https://github.com/Fansirsqi/Sesame-TK'
    }
  }, " Github\u5F00\u6E90\u5730\u5740 "), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 5,
      color: '#999',
      fontSize: 14
    }
  }, "\uD83E\uDD73 Power by ", /*#__PURE__*/React.createElement(Text, {
    link: {
      href: 'https://semi.design/'
    }
  }, "Semi.Design"), " \xA9 2025-12-11 With ", /*#__PURE__*/React.createElement(Text, {
    link: {
      href: 'https://github.com/Fansirsqi'
    }
  }, "Byseven Offical"), " "), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 5,
      color: '#999',
      fontSize: 14
    }
  }, "Ver: ", window.HOOK.getBuildInfo())), /*#__PURE__*/React.createElement(SideSheet, {
    title: /*#__PURE__*/React.createElement("span", {
      style: {
        fontSize: 16,
        fontWeight: 600
      }
    }, sheetConfig.title),
    visible: sheetVisible,
    onCancel: () => setSheetVisible(false),
    width: "100%",
    placement: "right",
    bodyStyle: {
      padding: 10
    }
  }, /*#__PURE__*/React.createElement(Text, null, sheetConfig.desc), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '10px 16px',
      borderBottom: '1px solid var(--semi-color-border)'
    }
  }, /*#__PURE__*/React.createElement(Input, {
    prefix: /*#__PURE__*/React.createElement(IconSearch, null),
    placeholder: "\u641C\u7D22\u540D\u79F0...",
    value: sheetSearch,
    onChange: handleSearch
  }), /*#__PURE__*/React.createElement("div", {
    style: {
      marginTop: 10,
      display: 'flex',
      justifyContent: 'space-between',
      alignItems: 'center'
    }
  }, /*#__PURE__*/React.createElement(Checkbox, {
    onChange: toggleAll,
    checked: false
  }, "\u5168\u9009/\u53CD\u9009 (\u5F53\u524D\u53EF\u89C1)"), /*#__PURE__*/React.createElement(Text, {
    size: "small",
    type: "secondary"
  }, "\u5DF2\u9009: ", selectedIds.size))), /*#__PURE__*/React.createElement("div", {
    style: {
      padding: '0 16px',
      overflowY: 'auto',
      height: 'calc(100vh - 180px)'
    }
  }, renderedSheetList), /*#__PURE__*/React.createElement("div", {
    className: "sheet-footer",
    style: {
      padding: '16px',
      justifyContent: 'space-around',
      position: 'absolute',
      bottom: 0,
      width: '100%',
      background: 'var(--semi-color-bg-1)'
    }
  }, /*#__PURE__*/React.createElement(Button, {
    style: {
      marginRight: 10
    },
    onClick: () => setSheetVisible(false)
  }, "\u53D6\u6D88"), /*#__PURE__*/React.createElement(Button, {
    theme: "solid",
    onClick: saveSheet
  }, "\u4FDD\u5B58"))));
};
ReactDOM.render(/*#__PURE__*/React.createElement(App, null), document.getElementById('app'));
