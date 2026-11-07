package com.caramelo.teleonline

import com.caramelo.settings.ChannelRow

internal object TeleonlineDirectory {
    // IDs from the site's public WordPress taxonomy, classified by its navigation menu.
    private val terms = """
118|Deportivos|0
141|Economía|0
405|Educación|0
412|Entretenimiento|0
406|General|0
79|Infantiles|0
117|Informativos|0
77|Musicales|0
421|Películas|0
124|Religión|0
129|Streaming|0
154|Afganistán|1
156|Albania|1
148|Alemania|1
159|Andorra|1
160|Angola|1
161|Anguila|1
163|Antigua y Barbuda|1
347|Arabia Saudita|1
157|Argelia|1
164|Argentina|1
165|Armenia|1
166|Aruba|1
167|Australia|1
168|Austria|1
169|Azerbaiyán|1
170|Bahamas|1
171|Bahréin|1
172|Bangladesh|1
173|Barbados|1
176|Belice|1
178|Bermudas|1
174|Bielorrusia|1
180|Bolivia|1
181|Bonaire|1
182|Bosnia y Herzegovina|1
183|Botsuana|1
185|Brasil|1
188|Brunéi|1
189|Bulgaria|1
190|Burkina Faso|1
191|Burundi|1
179|Bután|1
175|Bélgica|1
195|Cabo Verde|1
192|Camboya|1
193|Camerún|1
194|Canadá|1
332|Catar|1
198|Chad|1
199|Chile|1
200|China|1
210|Chipre|1
395|Ciudad del Vaticano|1
203|Colombia|1
204|Comoras|1
315|Corea del Norte|1
360|Corea del Sur|1
262|Costa de Marfil|1
206|Costa Rica|1
207|Croacia|1
208|Cuba|1
209|Curazao|1
213|Dinamarca|1
215|Dominica|1
218|Ecuador|1
219|Egipto|1
389|Emiratos Árabes Unidos|1
221|Eritrea|1
354|Eslovaquia|1
355|Eslovenia|1
24|España|1
391|Estados Unidos|1
222|Estonia|1
223|Etiopía|1
327|Filipinas|1
227|Finlandia|1
226|Fiyi|1
90|Francia|1
232|Gabón|1
233|Gambia|1
234|Georgia|1
236|Ghana|1
237|Gibraltar|1
240|Granada|1
238|Grecia|1
239|Groenlandia|1
241|Guadalupe|1
242|Guam|1
243|Guatemala|1
229|Guayana Francesa|1
245|Guinea|1
220|Guinea Ecuatorial|1
246|Guinea-Bissau|1
247|Guyana|1
248|Haití|1
250|Honduras|1
251|Hong Kong|1
252|Hungría|1
254|India|1
255|Indonesia|1
257|Irak|1
258|Irlanda|1
95|Irán|1
253|Islandia|1
196|Islas Caimán|1
205|Islas Cook|1
225|Islas Feroe|1
224|Islas Malvinas|1
317|Islas Marianas del Norte|1
356|Islas Salomón|1
383|Islas Turcas y Caicos|1
187|Islas Vírgenes Británicas|1
386|Islas Vírgenes de los Estados Unidos|1
260|Israel|1
107|Italia|1
263|Jamaica|1
264|Japón|1
266|Jordania|1
267|Kazajstán|1
268|Kenia|1
272|Kirguistán|1
269|Kiribati|1
270|Kosovo|1
271|Kuwait|1
273|Laos|1
274|Letonia|1
277|Liberia|1
278|Libia|1
279|Liechtenstein|1
280|Lituania|1
281|Luxemburgo|1
275|Líbano|1
282|Macao|1
316|Macedonia del Norte|1
283|Madagascar|1
285|Malasia|1
284|Malawi|1
286|Maldivas|1
288|Malta|1
287|Malí|1
136|Marruecos|1
290|Martinica|1
292|Mauricio|1
291|Mauritania|1
293|Mayotte|1
295|Micronesia|1
296|Moldavia|1
298|Mongolia|1
299|Montenegro|1
300|Montserrat|1
302|Mozambique|1
303|Myanmar|1
294|México|1
297|Mónaco|1
304|Namibia|1
305|Nauru|1
306|Nepal|1
310|Nicaragua|1
312|Nigeria|1
313|Niue|1
318|Noruega|1
308|Nueva Caledonia|1
309|Nueva Zelanda|1
311|Níger|1
319|Omán|1
320|Pakistán|1
322|Palestina|1
323|Panamá|1
324|Papúa Nueva Guinea|1
325|Paraguay|1
307|Países Bajos|1
326|Perú|1
230|Polinesia Francesa|1
329|Polonia|1
330|Portugal|1
331|Puerto Rico|1
390|Reino Unido|1
197|República Centroafricana|1
211|República Checa|1
333|República del Congo|1
212|República Democrática del Congo|1
216|República Dominicana|1
337|Reunión|1
336|Ruanda|1
334|Rumania|1
335|Rusia|1
345|Samoa|1
158|Samoa Americana|1
340|San Cristóbal y Nieves|1
346|San Marino|1
343|San Pedro y Miquelón|1
344|San Vicente y las Granadinas|1
341|Santa Lucía|1
348|Senegal|1
349|Serbia|1
350|Seychelles|1
351|Sierra Leona|1
352|Singapur|1
353|Sint Maarten|1
370|Siria|1
357|Somalia|1
363|Sri Lanka|1
367|Suazilandia|1
358|Sudáfrica|1
364|Sudán|1
361|Sudán del Sur|1
368|Suecia|1
369|Suiza|1
365|Surinam|1
375|Tailandia|1
372|Taiwán|1
374|Tanzania|1
373|Tayikistán|1
217|Timor Oriental|1
376|Togo|1
378|Tonga|1
379|Trinidad y Tobago|1
382|Turkmenistán|1
381|Turquía|1
384|Tuvalu|1
380|Túnez|1
388|Ucrania|1
387|Uganda|1
392|Uruguay|1
393|Uzbekistán|1
394|Vanuatu|1
396|Venezuela|1
397|Vietnam|1
398|Wallis y Futuna|1
400|Yemen|1
214|Yibuti|1
401|Zambia|1
402|Zimbabue|1
""".trim().lineSequence().map { row ->
        val (id, name, kind) = row.split('|')
        val country = kind == "1"
        ChannelRow("term:$id", name, country, default = !country)
    }.toList()

    val rows = listOf(ChannelRow("all", "All live channels")) + terms
}
