/* Runs only after an explicit Read page action; excludes form fields. */
(() => {
  const source = document.querySelector("article") || document.querySelector("main") || document.body;
  if (!source) return {title: document.title, text: ""};
  const copy = source.cloneNode(true);
  copy.querySelectorAll("script,style,noscript,nav,header,footer,aside,form,input,textarea,select,button,iframe,svg,[hidden],[aria-hidden='true'],[contenteditable]").forEach(n => n.remove());
  const paragraphs = [...copy.querySelectorAll("h1,h2,h3,p,li,blockquote,pre")]
    .filter(n => !n.parentElement?.closest("p,li,blockquote,pre"))
    .map(n => n.textContent.replace(/\s+/g, " ").trim()).filter(Boolean);
  return {title: document.title, text: (paragraphs.join("\n\n") || copy.textContent).slice(0, 100000)};
})();
