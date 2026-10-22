(function () {
  'use strict';

  function strip(html) {
    var d = document.createElement('div');
    d.innerHTML = String(html == null ? '' : html).replace(/<br\s*\/?>/gi, '\n');
    return (d.textContent || '').replace(/\u00a0/g, ' ').trim();
  }

  function firstLine(html) {
    return strip(String(html || '').split(/<br\s*\/?>/i)[0]);
  }

  function splitBlocks(html) {
    return String(html || '').split(/-{3,}/).map(function (s) {
      return s.replace(/^(?:\s*<br\s*\/?>\s*)+/i, '');
    }).filter(function (s) {
      return strip(s) !== '';
    });
  }

  function parseWeeks(text, type) {
    var set = {};
    String(text || '')
      .replace(/[，、]/g, ',')
      .replace(/[－—–~～]/g, '-')
      .replace(/\s+/g, '')
      .split(',')
      .forEach(function (part) {
        if (!part) return;
        var m = part.match(/^(\d+)-(\d+)$/);
        if (m) {
          var a = parseInt(m[1], 10);
          var b = parseInt(m[2], 10);
          if (a > b) {
            var t = a;
            a = b;
            b = t;
          }
          for (var i = a; i <= b && i <= 60; i++) set[i] = true;
        } else if (/^\d+$/.test(part)) {
          set[parseInt(part, 10)] = true;
        }
      });
    var arr = Object.keys(set).map(Number).sort(function (a, b) {
      return a - b;
    });
    if (type === '单' || type === '单周') {
      arr = arr.filter(function (w) {
        return w % 2 === 1;
      });
    }
    if (type === '双' || type === '双周') {
      arr = arr.filter(function (w) {
        return w % 2 === 0;
      });
    }
    return arr;
  }

  function compress(nums) {
    nums = Array.from(new Set((nums || []).map(Number).filter(function (n) {
      return n > 0;
    }))).sort(function (a, b) {
      return a - b;
    });
    var parts = [];
    var i = 0;
    while (i < nums.length) {
      var j = i;
      while (j + 1 < nums.length && nums[j + 1] === nums[j] + 1) j++;
      parts.push(i === j ? String(nums[i]) : nums[i] + '-' + nums[j]);
      i = j + 1;
    }
    return parts.join(',');
  }

  function weekTypeOf(text) {
    var s = String(text || '');
    var single = s.indexOf('单') >= 0;
    var dual = s.indexOf('双') >= 0;
    if (single && !dual) return '单周';
    if (dual && !single) return '双周';
    return '全部';
  }

  function parseWeekText(text) {
    var s = String(text || '');
    var wt = weekTypeOf(s);
    var m = s.match(/(\d[\d,\-]*)\s*(?:周|[(（](?:全部|单周|双周|单|双)[)）])/);
    var weeks = m ? m[1] : '';
    if (!weeks) {
      var m2 = s.match(/(?:第)?\s*(\d{1,2}(?:\s*[-~—]\s*\d{1,2})?(?:\s*,\s*\d{1,2}(?:\s*[-~—]\s*\d{1,2})?)*)\s*周/);
      if (m2) weeks = m2[1].replace(/\s+/g, '');
    }
    return {weeks: weeks, weekType: wt};
  }

  function numbersFrom(text) {
    return parseWeeks(String(text || '').replace(/[^\d,\-~—]/g, '').replace(/[~—]/g, '-'), '全部');
  }

  function make(name, teacher, room, className, day, start, end, weeks, weekType, hours, courseType) {
    name = String(name || '').trim();
    if (!name || !day || !start || !end) return null;
    return {
      name: name,
      teacher: String(teacher || '').trim(),
      room: String(room || '').trim(),
      className: String(className || '').trim(),
      day: day,
      start: start,
      end: end,
      weeks: weeks || '1-20',
      weekType: weekType || '全部',
      hours: String(hours || '').trim(),
      courseType: String(courseType || '').trim()
    };
  }

  function merge(list) {
    var map = {};
    var out = [];
    list.forEach(function (c) {
      if (!c || !c.name) return;
      var key = [c.name, c.teacher, c.room, c.day, c.start, c.end, c.weekType].join('|');
      var old = map[key];
      if (old) {
        old.weeks = compress(parseWeeks(old.weeks, old.weekType).concat(parseWeeks(c.weeks, c.weekType)));
      } else {
        map[key] = c;
        out.push(c);
      }
    });
    out.sort(function (a, b) {
      return (a.day - b.day) || (a.start - b.start) ||
        ((parseWeeks(a.weeks, a.weekType)[0] || 0) - (parseWeeks(b.weeks, b.weekType)[0] || 0)) ||
        String(a.name).localeCompare(String(b.name), 'zh');
    });
    return out;
  }

  function parseQzBlock(html, day, start, end) {
    var name = firstLine(html);
    if (!name) return null;
    var hours = '';
    var ctype = '';
    var m = name.match(/^(.*?)\s*\[([^\]]+)\]\s*\[([^\]]+)\]/);
    if (m) {
      name = m[1].trim();
      hours = m[2].trim();
      ctype = m[3].trim();
    }
    if (!name) return null;
    var holder = document.createElement('div');
    holder.innerHTML = html;
    var fonts = holder.querySelectorAll('font[title]');
    var teachers = [];
    var classText = '';
    var room = '';
    for (var i = 0; i < fonts.length; i++) {
      var title = fonts[i].getAttribute('title') || '';
      var value = (fonts[i].textContent || '').replace(/\s+/g, ' ').trim();
      if (title === '老师' && value) teachers.push(value);
      else if (title === '班级') classText = (fonts[i].textContent || '').replace(/\s+/g, '').trim();
      else if ((title === '上课地点' || title === '教室') && value) room = value;
    }
    if (!classText) {
      for (var j = 0; j < fonts.length; j++) {
        if ((fonts[j].getAttribute('title') || '') === '周次(节次)') {
          classText = (fonts[j].textContent || '').replace(/\s+/g, '').trim();
          break;
        }
      }
    }
    var w = parseWeekText(classText);
    var className = classText.replace(/\(\d+\).*$/, '')
      .replace(/(\d[\d,\-]*)\((全部|单周|双周|单|双)\).*$/, '').trim();
    return make(name, teachers.join('、'), room, className, day, start, end,
      w.weeks, w.weekType, hours, ctype);
  }

  function captureQz(doc) {
    var table = doc.querySelector('#kbtable');
    if (!table) return null;
    var rows = table.querySelectorAll('tr');
    var courses = [];
    var notes = '';
    for (var r = 1; r < rows.length; r++) {
      var row = rows[r];
      var th = row.querySelector('th');
      var label = th ? (th.textContent || '').trim() : '';
      var cells = row.querySelectorAll('td');
      if (label.indexOf('备注') >= 0) {
        if (cells[0]) notes = (cells[0].textContent || '').trim();
        continue;
      }
      var mp = label.match(/第(\d+)\.(\d+)节/) || label.match(/第(\d+)节/);
      if (!mp) continue;
      var start = parseInt(mp[1], 10);
      var end = mp[2] ? parseInt(mp[2], 10) : start;
      for (var i = 0; i < Math.min(7, cells.length); i++) {
        var divs = cells[i].querySelectorAll('div.kbcontent');
        var blocks = [];
        if (divs.length) {
          for (var j = 0; j < divs.length; j++) {
            blocks = blocks.concat(splitBlocks(divs[j].innerHTML));
          }
        } else if ((cells[i].textContent || '').trim()) {
          blocks = [cells[i].innerHTML];
        }
        for (var k = 0; k < blocks.length; k++) {
          var c = parseQzBlock(blocks[k], i + 1, start, end);
          if (c) courses.push(c);
        }
      }
    }
    if (!courses.length) return null;
    var sem = '';
    var semesters = [];
    var sel = doc.querySelector('#xnxq01id');
    if (sel) {
      sem = sel.value || '';
      for (var s = 0; s < sel.options.length; s++) {
        var v = sel.options[s].value;
        if (v && semesters.indexOf(v) < 0) semesters.push(v);
      }
    }
    return okResult(merge(courses), {semester: sem, semesters: semesters, notes: notes});
  }

  function looksRoom(line) {
    if (/^(教师|老师|班级|周次|时间|备注)/.test(line)) return false;
    if (/(?:楼|室|馆|场|厅|机房|实验室|中心)[A-Za-z0-9\-]*/.test(line)) return true;
    if (/^[A-Za-z]?\d{1,2}[-#]\d{2,4}$/.test(line)) return true;
    if (/^[A-Za-z]{1,3}\d{2,4}$/.test(line)) return true;
    return false;
  }

  function blocksFromText(text) {
    var lines = String(text || '').replace(/\r/g, '').split('\n').map(function (s) {
      return s.trim();
    });
    var chunks = [];
    var cur = [];
    for (var i = 0; i < lines.length; i++) {
      if (!lines[i] || /^-{3,}$/.test(lines[i])) {
        if (cur.length) {
          chunks.push(cur.join('\n'));
          cur = [];
        }
      } else {
        cur.push(lines[i]);
      }
    }
    if (cur.length) chunks.push(cur.join('\n'));
    return chunks.length ? chunks : [''];
  }

  function parsePlainBlock(text, day, start, end) {
    var lines = String(text || '').split('\n').map(function (s) {
      return s.replace(/\u00a0/g, ' ').trim();
    }).filter(Boolean);
    if (!lines.length) return null;
    var joined = lines.join(' ');
    if (lines.length === 1 && /^(星期|周)[一二三四五六日天]$/.test(lines[0])) return null;
    if (/^(备注|时间|节次|上午|下午|晚上|课程表|课表)$/.test(lines[0])) return null;
    var name = lines[0];
    name = name.replace(/\s*(?:第)?\d[\d,\-]*\s*周.*$/, '')
      .replace(/[(（](?:单|双|单周|双周|全部)[)）]\s*$/, '').trim();
    if (name.length < 2 || name.length > 80) return null;
    if (/^(备注|时间|节次|上午|下午|晚上|课程表|课表)$/.test(name)) return null;
    var room = '';
    var teacher = '';
    var className = '';
    var tm = joined.match(/(?:教师|老师)\s*[:：]\s*([^\s,，;；]+)/);
    if (tm) teacher = tm[1];
    var rm = joined.match(/(?:教室|地点|上课地点)\s*[:：]\s*([^\s,，;；]+)/);
    if (rm) room = rm[1];
    for (var i = 1; i < lines.length; i++) {
      var line = lines[i];
      if (/周/.test(line)) continue;
      if (!room && looksRoom(line)) {
        room = line;
        continue;
      }
      if (!className && /班/.test(line)) {
        className = line;
        continue;
      }
      if (!teacher && /^[\u4e00-\u9fa5·]{2,8}$/.test(line)) {
        teacher = line;
      }
    }
    if (!room) {
      for (var j = 1; j < lines.length; j++) {
        if (/周/.test(lines[j])) continue;
        if (looksRoom(lines[j])) {
          room = lines[j];
          break;
        }
      }
    }
    var w = parseWeekText(joined);
    if (!w.weeks) {
      if (lines.length < 2 || (!room && !teacher)) return null;
      w.weeks = '1-20';
      w.weekType = '全部';
    }
    return make(name, teacher, room, className, day, start, end,
      w.weeks, w.weekType, '', '');
  }

  function captureGeneric(doc) {
    var tables = doc.querySelectorAll('table');
    var best = null;
    var bestScore = 0;
    for (var t = 0; t < tables.length; t++) {
      var rows = tables[t].rows;
      if (!rows || rows.length < 4) continue;
      var dayHits = 0;
      for (var r = 0; r < Math.min(rows.length, 4); r++) {
        var txt = rows[r].textContent || '';
        if (/星期[一二三四五六日天]|周[一二三四五六日天]/.test(txt)) dayHits++;
      }
      var score = rows.length * 5 + dayHits * 50;
      if (dayHits >= 2 && score > bestScore) {
        best = tables[t];
        bestScore = score;
      }
    }
    if (!best) return null;
    var rows2 = best.rows;
    var headerIndex = -1;
    var dayMap = {};
    for (var i = 0; i < rows2.length; i++) {
      var cells = rows2[i].cells;
      var map = {};
      var hits = 0;
      for (var j = 0; j < cells.length; j++) {
        var m = (cells[j].textContent || '').replace(/\s+/g, '')
          .match(/(?:星期|周)([一二三四五六日天])/);
        if (m) {
          var d = '一二三四五六日天'.indexOf(m[1]);
          map[j] = d === 7 ? 7 : d + 1;
          hits++;
        }
      }
      if (hits >= 3) {
        headerIndex = i;
        dayMap = map;
        break;
      }
    }
    if (headerIndex < 0) return null;
    var courses = [];
    for (var r2 = headerIndex + 1; r2 < rows2.length; r2++) {
      var cells2 = rows2[r2].cells;
      var period = null;
      for (var c = 0; c < Math.min(3, cells2.length); c++) {
        var raw = (cells2[c].textContent || '').replace(/\u00a0/g, ' ').trim();
        if (!raw || raw.length > 20) continue;
        var pm = raw.match(/第?\s*(\d+)\s*[-~—]\s*(\d+)\s*节?/) ||
          raw.match(/第?\s*(\d+)\s*[.．,，、]\s*(\d+)\s*节?/) ||
          raw.match(/^\s*第?\s*(\d+)\s*节?\s*$/);
        if (pm) {
          var ps = parseInt(pm[1], 10);
          var pe = pm[2] ? parseInt(pm[2], 10) : ps;
          if (pe < ps) {
            var tmp = ps;
            ps = pe;
            pe = tmp;
          }
          period = {start: ps, end: pe};
          break;
        }
      }
      if (!period) continue;
      for (var j2 = 0; j2 < cells2.length; j2++) {
        var day = dayMap[j2];
        if (!day) continue;
        var cell = cells2[j2];
        var divs = cell.querySelectorAll('div.kbcontent');
        var blocks = [];
        if (divs.length) {
          for (var q = 0; q < divs.length; q++) {
            blocks = blocks.concat(splitBlocks(divs[q].innerHTML));
          }
        } else {
          blocks = blocksFromText(cell.innerText || cell.textContent || '');
        }
        for (var b = 0; b < blocks.length; b++) {
          var item = divs.length
            ? parseQzBlock(blocks[b], day, period.start, period.end)
            : parsePlainBlock(blocks[b], day, period.start, period.end);
          if (item) courses.push(item);
        }
      }
    }
    if (!courses.length) return null;
    return okResult(merge(courses), {semester: inferSemester(doc), semesters: [], notes: ''});
  }

  function cnDay(text) {
    var m = String(text || '').match(/(?:星期|周)([一二三四五六日天])/);
    if (!m) return 0;
    var d = '一二三四五六日天'.indexOf(m[1]);
    return d === 7 ? 7 : d + 1;
  }

  function tryZf(doc) {
    var href = location.href || '';
    if (!/jwglxt/i.test(href)) return null;
    var m = location.pathname.match(/^(.*?\/jwglxt)(?:\/|$)/i);
    if (!m) return null;
    var base = location.origin + m[1];
    var xnm = '';
    var xqm = '';
    var xnmSel = doc.querySelector('#xnm');
    var xqmSel = doc.querySelector('#xqm');
    if (xnmSel && xnmSel.value) xnm = xnmSel.value;
    if (xqmSel && xqmSel.value) xqm = xqmSel.value;
    if (!xnm || !xqm) {
      var query = location.search.replace(/^\?/, '').split('&');
      for (var i = 0; i < query.length; i++) {
        var kv = query[i].split('=');
        if (kv[0] === 'xnm' && !xnm) xnm = decodeURIComponent(kv[1] || '');
        if (kv[0] === 'xqm' && !xqm) xqm = decodeURIComponent(kv[1] || '');
      }
    }
    var now = new Date();
    var y = now.getFullYear();
    var mo = now.getMonth() + 1;
    if (!xnm) xnm = String(mo >= 8 ? y : y - 1);
    if (!xqm) xqm = (mo >= 8 || mo === 1) ? '3' : '12';
    var list = null;
    try {
      var xhr = new XMLHttpRequest();
      xhr.open('POST', base + '/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151', false);
      xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded; charset=UTF-8');
      xhr.setRequestHeader('X-Requested-With', 'XMLHttpRequest');
      xhr.send('xnm=' + encodeURIComponent(xnm) + '&xqm=' + encodeURIComponent(xqm) + '&kzlx=ck&xsdm=');
      if (xhr.status >= 200 && xhr.status < 300) {
        var data = JSON.parse(xhr.responseText);
        if (data && data.kbList && data.kbList.length) list = data.kbList;
      }
    } catch (e) {
    }
    if (!list) return null;
    var courses = [];
    for (var k = 0; k < list.length; k++) {
      var item = list[k] || {};
      var name = strip(item.kcmc || '');
      if (!name) continue;
      var day = parseInt(String(item.xqj || '').replace(/\D/g, ''), 10);
      if (!day || day < 1 || day > 7) day = cnDay(item.xqj || '');
      if (!day) continue;
      var jcs = String(item.jcs || '');
      var pm = jcs.match(/(\d+)\s*[-~—]\s*(\d+)/) || jcs.match(/(\d+)/);
      if (!pm) continue;
      var start = parseInt(pm[1], 10);
      var end = pm[2] ? parseInt(pm[2], 10) : start;
      if (end < start) {
        var t = start;
        start = end;
        end = t;
      }
      var zcd = String(item.zcd || '');
      var wt = weekTypeOf(zcd);
      var weeks = compress(numbersFrom(zcd)) || '1-20';
      var teachers = String(item.xm || '').replace(/[,，]/g, '、').trim();
      var room = String(item.cdmc || item.jxdd || '').trim();
      var className = String(item.jxbmc || '').trim();
      courses.push(make(name, teachers, room, className, day, start, end, weeks, wt, '', ''));
    }
    if (!courses.length) return null;
    var semesters = [];
    try {
      var xs = doc.querySelector('#xnm');
      if (xs) {
        for (var a = 0; a < xs.options.length; a++) {
          var yy = parseInt(xs.options[a].value, 10);
          if (yy) {
            var s1 = yy + '-' + (yy + 1) + '-1';
            var s2 = yy + '-' + (yy + 1) + '-2';
            if (semesters.indexOf(s1) < 0) semesters.push(s1);
            if (semesters.indexOf(s2) < 0) semesters.push(s2);
          }
        }
      }
    } catch (e) {
    }
    var semText = xnm + '-' + (parseInt(xnm, 10) + 1) + '-' +
      (xqm === '12' ? '2' : xqm === '16' ? '3' : '1');
    return okResult(merge(courses), {semester: semText, semesters: semesters, notes: ''});
  }

  function inferSemester(doc) {
    try {
      var sel = doc.querySelector('#xnxq01id');
      if (sel && sel.value) return sel.value;
      var text = ((doc.body && doc.body.innerText) || '').slice(0, 4000);
      var m = text.match(/(20\d{2})\s*[-—~至]\s*(20\d{2})\s*学?年?\s*第?\s*([一二12])\s*学期/);
      if (m) {
        var term = (m[3] === '一' || m[3] === '1') ? '1' : '2';
        return m[1] + '-' + m[2] + '-' + term;
      }
      var m2 = text.match(/(20\d{2})-(20\d{2})-([12])/);
      if (m2) return m2[1] + '-' + m2[2] + '-' + m2[3];
    } catch (e) {
    }
    return '';
  }

  function pad(n) {
    return n < 10 ? '0' + n : '' + n;
  }

  function nowText() {
    var d = new Date();
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' +
      pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
  }

  function okResult(courses, extra) {
    return {
      ok: true,
      via: 'web',
      school: '页面抓取',
      semester: extra.semester || '',
      semesters: extra.semesters || [],
      fetchedAt: nowText(),
      notes: extra.notes || '',
      courses: courses
    };
  }

  window.__captureTable = function () {
    try {
      var qz = captureQz(document);
      if (qz && qz.courses.length) return qz;
      var zf = tryZf(document);
      if (zf && zf.courses.length) return zf;
      var generic = captureGeneric(document);
      if (generic && generic.courses.length) return generic;
      return {ok: false, error: '当前页面没有识别到课表，请先打开「学生课表 / 我的课表」再抓取'};
    } catch (e) {
      return {ok: false, error: '抓取失败：' + (e && e.message ? e.message : e)};
    }
  };
})();
