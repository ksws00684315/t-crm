/**
 * Measures visible table-cell content against the column direction.
 *
 * No dependencies. In a browser, evaluate this file then call auditTableAlignment().
 * Node tests require the pure classifier via module.exports.
 *
 * Content is the union of visible text and leaf widgets (tags, icons, inputs). A
 * stretch flex/grid wrapper is not itself content: text-align does not move it, and
 * counting its box would hide a left-hugging group inside a full-width cell.
 *
 * Left-aligned columns may end in an ellipsis, so their content is clipped to the cell
 * before judging. Centered columns hold short values (dates, states, amounts, actions) that
 * must not be cut, so their content is never clipped: spilling past the content box is an
 * "overflow" problem, which means the column is too narrow for that language.
 */
(function (root, factory) {
  var api = factory();
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  root.auditTableAlignment = api.auditTableAlignment;
  root.classifyAlignment = api.classifyAlignment;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  var TOLERANCE_PX = 2;
  var LEAF =
    "img, svg, canvas, video, input, button, textarea, select, .el-tag, .el-icon, .el-checkbox, .el-switch, .el-radio";

  /**
   * @param {{
   *   align: "center" | "left" | "right" | "none",
   *   contentStart: number | null,
   *   contentEnd: number | null,
   *   cellStart: number,
   *   cellEnd: number,
   *   headerAlign: string,
   *   cellAlign: string
   * }} sample
   * @returns {{ reason: "center" | "left" | "direction" | "overflow", deltaPx: number }[]}
   */
  function classifyAlignment(sample) {
    var problems = [];
    if (sample.headerAlign && sample.cellAlign && sample.headerAlign !== sample.cellAlign) {
      problems.push({ reason: "direction", deltaPx: 0 });
    }
    if (sample.contentStart == null || sample.contentEnd == null) return problems;
    if (sample.align === "center") {
      var spill = Math.max(sample.cellStart - sample.contentStart, sample.contentEnd - sample.cellEnd);
      if (spill > TOLERANCE_PX) problems.push({ reason: "overflow", deltaPx: roundPx(spill) });
      var contentCenter = (sample.contentStart + sample.contentEnd) / 2;
      var cellCenter = (sample.cellStart + sample.cellEnd) / 2;
      var centerDelta = Math.abs(contentCenter - cellCenter);
      if (centerDelta > TOLERANCE_PX) problems.push({ reason: "center", deltaPx: roundPx(centerDelta) });
    } else if (sample.align === "left") {
      var leftDelta = Math.abs(sample.contentStart - sample.cellStart);
      if (leftDelta > TOLERANCE_PX) problems.push({ reason: "left", deltaPx: roundPx(leftDelta) });
    }
    return problems;
  }

  function roundPx(value) {
    return Math.round(value * 100) / 100;
  }

  function alignOf(cell) {
    if (!cell || !cell.classList) return "none";
    if (cell.classList.contains("is-center")) return "center";
    if (cell.classList.contains("is-left")) return "left";
    if (cell.classList.contains("is-right")) return "right";
    return "none";
  }

  function isShown(el) {
    if (!el || el.nodeType !== 1) return false;
    if (el.closest(".sr-only")) return false;
    var style = el.ownerDocument.defaultView.getComputedStyle(el);
    if (style.display === "none" || style.visibility === "hidden") return false;
    var rects = el.getClientRects();
    return rects.length > 0 && (rects[0].width > 0 || rects[0].height > 0);
  }

  function hasPaintedBox(el) {
    var style = el.ownerDocument.defaultView.getComputedStyle(el);
    var background = style.backgroundColor;
    if (background && background !== "transparent" && background !== "rgba(0, 0, 0, 0)") return true;
    var sides = ["Top", "Right", "Bottom", "Left"];
    for (var i = 0; i < sides.length; i += 1) {
      var width = parseFloat(style["border" + sides[i] + "Width"]) || 0;
      var color = style["border" + sides[i] + "Color"];
      if (width > 0 && color && color !== "transparent" && color !== "rgba(0, 0, 0, 0)") return true;
    }
    return false;
  }

  function contentBox(el) {
    var rect = el.getBoundingClientRect();
    var style = el.ownerDocument.defaultView.getComputedStyle(el);
    var start =
      rect.left +
      (parseFloat(style.borderLeftWidth) || 0) +
      (parseFloat(style.paddingLeft) || 0);
    var end =
      rect.right -
      (parseFloat(style.borderRightWidth) || 0) -
      (parseFloat(style.paddingRight) || 0);
    return { start: start, end: end };
  }

  function unionContent(cell, doc, clip) {
    var host = cell.querySelector(":scope > .cell") || cell;
    var clipBox = clip ? contentBox(host) : { start: -Infinity, end: Infinity };
    var start = Infinity;
    var end = -Infinity;
    var found = false;
    function consider(rect) {
      if (!rect || (rect.width === 0 && rect.height === 0)) return;
      // A left column may end in an ellipsis: judge only the visible slice. Centered columns pass
      // an unbounded box so content wider than the cell stays measurable as overflow.
      var left = Math.max(rect.left, clipBox.start);
      var right = Math.min(rect.right, clipBox.end);
      if (right - left <= 0) return;
      found = true;
      if (left < start) start = left;
      if (right > end) end = right;
    }
    var walker = doc.createTreeWalker(host, doc.defaultView.NodeFilter.SHOW_TEXT, {
      acceptNode: function (node) {
        if (!node.nodeValue || !node.nodeValue.trim()) return doc.defaultView.NodeFilter.FILTER_REJECT;
        var parent = node.parentElement;
        if (!parent || !isShown(parent) || parent.closest(".sr-only")) {
          return doc.defaultView.NodeFilter.FILTER_REJECT;
        }
        return doc.defaultView.NodeFilter.FILTER_ACCEPT;
      }
    });
    while (walker.nextNode()) {
      var range = doc.createRange();
      range.selectNodeContents(walker.currentNode);
      consider(range.getBoundingClientRect());
      range.detach();
    }
    var leaves = host.querySelectorAll(LEAF);
    for (var i = 0; i < leaves.length; i += 1) {
      if (isShown(leaves[i])) consider(leaves[i].getBoundingClientRect());
    }
    // A thumbnail placeholder paints a box around centered ink. Count that box, not only the glyph,
    // or a left-aligned image slot looks indented. Transparent layout wrappers stay out: counting
    // them would hide a left-packed flex group inside a full-width cell.
    var boxes = host.querySelectorAll("*");
    for (var b = 0; b < boxes.length; b += 1) {
      if (!isShown(boxes[b]) || !hasPaintedBox(boxes[b])) continue;
      consider(boxes[b].getBoundingClientRect());
    }
    if (!found) return null;
    return { start: start, end: end };
  }

  function tableName(root, index) {
    var host = root.closest(".data-table, [data-test]") || root;
    var label = host.getAttribute("data-test") || host.getAttribute("aria-label") || "";
    return label || "table-" + (index + 1);
  }

  function columnName(headers, index) {
    var header = headers[index];
    var text = header ? header.textContent.replace(/\s+/g, " ").trim() : "";
    return text || "col-" + (index + 1);
  }

  function pushProblems(problems, page, name, column, row, found) {
    for (var i = 0; i < found.length; i += 1) {
      problems.push({
        page: page,
        table: name,
        column: column,
        row: row,
        deltaPx: found[i].deltaPx,
        reason: found[i].reason
      });
    }
  }

  function measureCell(cell, doc) {
    if (!cell || !isShown(cell) || cell.colSpan > 1) return null;
    var box = contentBox(cell.querySelector(":scope > .cell") || cell);
    var align = alignOf(cell);
    var content = unionContent(cell, doc, align !== "center");
    return {
      align: align,
      contentStart: content ? content.start : null,
      contentEnd: content ? content.end : null,
      cellStart: box.start,
      cellEnd: box.end
    };
  }

  /**
   * Element Plus splits the header and body into two table elements inside one .el-table.
   * @param {Document} [doc]
   * @returns {{ page: string, table: string, column: string, row: string, deltaPx: number, reason: string }[]}
   */
  function auditTableAlignment(doc) {
    var documentRef = doc || (typeof document !== "undefined" ? document : null);
    if (!documentRef) throw new Error("auditTableAlignment requires a document");
    var page = documentRef.location ? documentRef.location.href : "";
    var problems = [];
    var roots = documentRef.querySelectorAll(".el-table");
    for (var t = 0; t < roots.length; t += 1) {
      var root = roots[t];
      if (root.closest(".el-table__fixed, .el-table__fixed-right")) continue;
      if (!isShown(root)) continue;
      var name = tableName(root, t);
      var headerTable = root.querySelector("table.el-table__header");
      var bodyTable = root.querySelector("table.el-table__body");
      var headers = headerTable ? headerTable.querySelectorAll("thead th") : [];
      var seenDirection = {};
      if (headerTable) {
        for (var h = 0; h < headers.length; h += 1) {
          var headerSample = measureCell(headers[h], documentRef);
          if (!headerSample) continue;
          pushProblems(
            problems,
            page,
            name,
            columnName(headers, h),
            "header",
            classifyAlignment({
              align: headerSample.align,
              contentStart: headerSample.contentStart,
              contentEnd: headerSample.contentEnd,
              cellStart: headerSample.cellStart,
              cellEnd: headerSample.cellEnd,
              headerAlign: headerSample.align,
              cellAlign: headerSample.align
            })
          );
        }
      }
      var rows = bodyTable ? bodyTable.querySelectorAll("tbody tr") : [];
      for (var r = 0; r < rows.length; r += 1) {
        var cells = rows[r].querySelectorAll("td");
        for (var c = 0; c < cells.length; c += 1) {
          var sample = measureCell(cells[c], documentRef);
          if (!sample) continue;
          var headerAlign = headers[c] ? alignOf(headers[c]) : sample.align;
          var found = classifyAlignment({
            align: sample.align,
            contentStart: sample.contentStart,
            contentEnd: sample.contentEnd,
            cellStart: sample.cellStart,
            cellEnd: sample.cellEnd,
            headerAlign: seenDirection[c] ? sample.align : headerAlign,
            cellAlign: sample.align
          });
          if (!seenDirection[c] && headerAlign !== sample.align && headerAlign !== "none") {
            seenDirection[c] = true;
          } else if (seenDirection[c]) {
            found = found.filter(function (item) {
              return item.reason !== "direction";
            });
          }
          pushProblems(problems, page, name, columnName(headers, c), String(r + 1), found);
        }
      }
    }
    return problems;
  }

  return { auditTableAlignment: auditTableAlignment, classifyAlignment: classifyAlignment };
});
